package com.aggitech.aggo.notify

import io.r2dbc.postgresql.api.PostgresqlConnection
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.withContext

/**
 * PostgreSQL [NotificationBackend] built on `LISTEN`/`NOTIFY` and the driver's
 * native [PostgresqlConnection.getNotifications] push stream.
 *
 * Holds its **own** dedicated connection — never one borrowed from [com.aggitech.aggo.runtime.AggoPool] —
 * because a `LISTEN` session must stay open for the listener's entire lifetime,
 * which is incompatible with pooled connection borrow/return semantics. Pass
 * the same [ConnectionFactory] the pool was built from
 * (e.g. `ConnectionFactories.get(...)`); creating it is the caller's responsibility
 * so [AggoListener] stays agnostic of how the rest of the application wires its pool.
 *
 * Reconnection is expressed entirely through [Flow.retryWhen] driven by
 * [reconnectPolicy] — no hand-rolled retry loop. Every retry restarts
 * [listenOnce] from scratch: a fresh connection, fresh `LISTEN` registrations,
 * fresh subscription to [PostgresqlConnection.getNotifications]. This is exactly
 * the behaviour [com.aggitech.aggo.notify.ReconnectPolicy.ExponentialBackoff] is
 * designed to drive.
 *
 * The returned [Flow] is cold — multiple collectors each open their own
 * connection. To multiplex one physical `LISTEN` connection across many
 * collectors, share it with the standard library operator:
 *
 * ```kotlin
 * val shared = listener.listen(UserEventsChannel)
 *     .shareIn(applicationScope, SharingStarted.WhileSubscribed(), replay = 0)
 * ```
 *
 * No bespoke multiplexing is implemented here — [Flow.shareIn] already solves it.
 */
class AggoListener(
    private val connectionFactory: ConnectionFactory,
    private val qualifier: ChannelNameQualifier = ChannelNameQualifier.None,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy.ExponentialBackoff(),
) : NotificationBackend {

    override fun <P : Any> listen(channel: NotifyChannel<P>): Flow<Notification<P>> =
        listenRaw(channel)
            .filter { it.channel == qualifier.qualify(channel.name) }
            .map { raw ->
                Notification(
                    channel = channel.name,
                    payload = channel.codec.decode(raw.parameter),
                    processId = raw.processId,
                )
            }

    override fun listenRaw(vararg channels: NotifyChannel<*>): Flow<RawNotification> {
        val qualifiedNames = channels.map { qualifier.qualify(it.name) }
        return listenOnce(qualifiedNames).retryWhen { cause, attempt ->
            if (cause is CancellationException) throw cause
            val delay = reconnectPolicy.nextDelay(attempt.toInt() + 1)
                ?: throw AggoListenerException("Max reconnect attempts reached", cause)
            kotlinx.coroutines.delay(delay)
            true
        }
    }

    /**
     * Opens one dedicated connection, issues `LISTEN` for every channel, and
     * relays the driver's notification stream until cancelled or the connection
     * drops. `UNLISTEN` + close always run in [NonCancellable] so a cancelled
     * collector still leaves the backend session clean.
     */
    private fun listenOnce(channelNames: List<String>): Flow<RawNotification> = flow {
        val connection = connectionFactory.create().awaitSingle() as PostgresqlConnection
        try {
            channelNames.forEach { name ->
                connection.createStatement("LISTEN \"$name\"").execute().awaitFirstOrNull()
            }
            emitAll(
                connection.notifications.asFlow()
                    .map { RawNotification(it.name, it.parameter, it.processId) },
            )
        } finally {
            withContext(NonCancellable) {
                runCatching {
                    channelNames.forEach { name ->
                        connection.createStatement("UNLISTEN \"$name\"").execute().awaitFirstOrNull()
                    }
                }
                connection.close().awaitFirstOrNull()
            }
        }
    }
}
