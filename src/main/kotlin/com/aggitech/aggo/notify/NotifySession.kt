package com.aggitech.aggo.notify

import com.aggitech.aggo.runtime.AggoUnsafe
import com.aggitech.aggo.runtime.Session
import kotlinx.coroutines.reactive.awaitFirstOrNull

/**
 * Emits a PostgreSQL notification manually, in the caller's current
 * session/transaction — for the cases where a [com.aggitech.aggo.schema.NotifyTrigger]
 * isn't viable (e.g. a payload that can't be expressed as one SQL expression).
 *
 * Goes through `pg_notify`, so it inherits the same commit-only delivery
 * guarantee as trigger-based notifications: a rolled-back transaction never
 * reaches listeners — that's native PostgreSQL behaviour, not something Aggo adds.
 *
 * **PostgreSQL-only.** MySQL/Oracle have no native pub/sub; their notifications
 * always travel through a [com.aggitech.aggo.schema.NotifyTrigger] writing to the
 * managed outbox table and [OutboxListener] polling it — business code never calls
 * `notify` directly on those dialects.
 *
 * `pg_notify` requires a raw connection statement (Aggo's [Session] DML surface
 * has no generic "execute arbitrary function" entry point), hence the [AggoUnsafe]
 * opt-in — the call itself is fully parameterized and injection-safe.
 */
@OptIn(AggoUnsafe::class)
suspend fun <P : Any> Session.notify(channel: NotifyChannel<P>, payload: P?) {
    val encoded = channel.codec.encode(payload)
    val statement = rawConnection()
        .createStatement("SELECT pg_notify($1, $2)")
        .bind(0, channel.name)
    if (encoded == null) statement.bindNull(1, String::class.java) else statement.bind(1, encoded)
    statement.execute().awaitFirstOrNull()
}
