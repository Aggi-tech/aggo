package com.aggitech.aggo.notify

/**
 * Encode/decode boundary for notification payloads.
 *
 * PostgreSQL `NOTIFY`/`pg_notify` and the outbox table both transport payloads
 * as `TEXT`. A [NotifyCodec] bridges that wire format to a domain type [P],
 * mirroring the role [com.aggitech.aggo.schema.Codec] plays for columns —
 * explicit, no reflection, safe for GraalVM native.
 */
interface NotifyCodec<P : Any> {
    fun encode(value: P?): String?
    fun decode(raw: String?): P?
}

/** Pass-through codec for plain text payloads. Blank strings decode to `null`. */
object StringNotifyCodec : NotifyCodec<String> {
    override fun encode(value: String?): String? = value
    override fun decode(raw: String?): String? = raw?.takeIf { it.isNotBlank() }
}

/** Codec for numeric payloads (e.g. a row's primary key). Unparseable input decodes to `null`. */
object LongNotifyCodec : NotifyCodec<Long> {
    override fun encode(value: Long?): String? = value?.toString()
    override fun decode(raw: String?): Long? = raw?.toLongOrNull()
}
