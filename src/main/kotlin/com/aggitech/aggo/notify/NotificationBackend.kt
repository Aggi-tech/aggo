package com.aggitech.aggo.notify

import kotlinx.coroutines.flow.Flow

/**
 * Dialect-agnostic delivery of notifications emitted by [com.aggitech.aggo.schema.NotifyTrigger]s
 * (or sent manually via [Session.notify][com.aggitech.aggo.notify.notify]).
 *
 * Business code depends only on this interface — [AggoListener] (PostgreSQL
 * `LISTEN`/`NOTIFY`) and [OutboxListener] (MySQL/Oracle outbox polling) are
 * interchangeable behind it.
 */
interface NotificationBackend {

    /**
     * Returns a cold [Flow] that emits decoded [Notification]s for [channel].
     *
     * Listening starts on the first `collect` and stops on cancellation —
     * collect it inside a [kotlinx.coroutines.coroutineScope] or a
     * structured application scope, never detached from a lifecycle.
     */
    fun <P : Any> listen(channel: NotifyChannel<P>): Flow<Notification<P>>

    /**
     * Listens to multiple channels through a single underlying connection/poll —
     * use this to avoid one dedicated connection per channel when a service
     * cares about many channels.
     */
    fun listenRaw(vararg channels: NotifyChannel<*>): Flow<RawNotification>
}
