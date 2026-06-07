package com.aggitech.aggo.notify

/**
 * A decoded notification delivered on a [NotifyChannel].
 *
 * @property processId the PID of the PostgreSQL backend that sent the
 * notification, or `0` for outbox-polling backends (MySQL/Oracle), which have
 * no equivalent concept.
 */
data class Notification<P : Any>(
    val channel: String,
    val payload: P?,
    val processId: Int = 0,
)

/** Undecoded notification — the channel name as delivered on the wire, with the raw text payload. */
data class RawNotification(
    val channel: String,
    val parameter: String?,
    val processId: Int,
)
