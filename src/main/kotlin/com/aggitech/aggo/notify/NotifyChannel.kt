package com.aggitech.aggo.notify

import com.aggitech.aggo.dialect.requireValidIdentifier

/**
 * A typed notification channel: a stable name plus the [NotifyCodec] used to
 * decode payloads delivered on it.
 *
 * The [name] is validated against the same identifier allowlist as table and
 * column names — `LISTEN`/`NOTIFY` channel names are interpolated into SQL by
 * [com.aggitech.aggo.dialect.TriggerDialect] and [com.aggitech.aggo.notify.AggoListener],
 * so this is the single point of defence against channel-name injection.
 *
 * ```kotlin
 * val UserEventsChannel = NotifyChannel("user_events", StringNotifyCodec)
 * val OrderChannel      = NotifyChannel("order_created", LongNotifyCodec)
 * ```
 */
data class NotifyChannel<P : Any>(
    val name: String,
    val codec: NotifyCodec<P>,
) {
    init {
        requireValidIdentifier(name)
    }
}
