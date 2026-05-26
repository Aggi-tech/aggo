package com.aggitech.aggo.schema

import com.aggitech.aggo.schema.ids.Tsid
import com.aggitech.aggo.schema.ids.Ulid
import io.r2dbc.spi.Row
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Bridge between a Kotlin domain type [V] and the raw value the R2DBC driver
 * accepts/returns. Codecs are looked up by the Column descriptor at compile time
 * (the application picks them explicitly when declaring the schema) — no runtime
 * reflection is involved in encode/decode.
 */
interface Codec<V> {
    /** Concrete JDBC/R2DBC type used for typed null binds. */
    val sqlType: Class<*>

    /** Convert a domain value to what the driver expects (or null). */
    fun encode(value: V?): Any?

    /** Convert a raw driver value to the domain type, or null. */
    fun decode(raw: Any?): V?
}

object StringCodec : Codec<String> {
    override val sqlType: Class<*> = String::class.java
    override fun encode(value: String?): Any? = value
    override fun decode(raw: Any?): String? = raw?.toString()
}

object IntCodec : Codec<Int> {
    override val sqlType: Class<*> = Int::class.javaObjectType
    override fun encode(value: Int?): Any? = value
    override fun decode(raw: Any?): Int? = (raw as? Number)?.toInt()
}

object LongCodec : Codec<Long> {
    override val sqlType: Class<*> = Long::class.javaObjectType
    override fun encode(value: Long?): Any? = value
    override fun decode(raw: Any?): Long? = (raw as? Number)?.toLong()
}

object BooleanCodec : Codec<Boolean> {
    override val sqlType: Class<*> = Boolean::class.javaObjectType
    override fun encode(value: Boolean?): Any? = value
    override fun decode(raw: Any?): Boolean? = when (raw) {
        null -> null
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        is String -> raw.toBoolean()
        else -> null
    }
}

object DoubleCodec : Codec<Double> {
    override val sqlType: Class<*> = Double::class.javaObjectType
    override fun encode(value: Double?): Any? = value
    override fun decode(raw: Any?): Double? = (raw as? Number)?.toDouble()
}

object FloatCodec : Codec<Float> {
    override val sqlType: Class<*> = Float::class.javaObjectType
    override fun encode(value: Float?): Any? = value
    override fun decode(raw: Any?): Float? = (raw as? Number)?.toFloat()
}

object ShortCodec : Codec<Short> {
    override val sqlType: Class<*> = Short::class.javaObjectType
    override fun encode(value: Short?): Any? = value
    override fun decode(raw: Any?): Short? = (raw as? Number)?.toShort()
}

object ByteArrayCodec : Codec<ByteArray> {
    override val sqlType: Class<*> = ByteArray::class.java
    override fun encode(value: ByteArray?): Any? = value
    override fun decode(raw: Any?): ByteArray? = raw as? ByteArray
}

object BigDecimalCodec : Codec<BigDecimal> {
    override val sqlType: Class<*> = BigDecimal::class.java
    override fun encode(value: BigDecimal?): Any? = value
    override fun decode(raw: Any?): BigDecimal? = when (raw) {
        null -> null
        is BigDecimal -> raw
        is Number -> BigDecimal.valueOf(raw.toDouble())
        is String -> BigDecimal(raw)
        else -> null
    }
}

object InstantCodec : Codec<Instant> {
    override val sqlType: Class<*> = OffsetDateTime::class.java
    override fun encode(value: Instant?): Any? = value?.let { OffsetDateTime.ofInstant(it, java.time.ZoneOffset.UTC) }
    override fun decode(raw: Any?): Instant? = when (raw) {
        null -> null
        is Instant -> raw
        is OffsetDateTime -> raw.toInstant()
        is LocalDateTime -> raw.toInstant(java.time.ZoneOffset.UTC)
        else -> null
    }
}

object LocalDateTimeCodec : Codec<LocalDateTime> {
    override val sqlType: Class<*> = LocalDateTime::class.java
    override fun encode(value: LocalDateTime?): Any? = value
    override fun decode(raw: Any?): LocalDateTime? = when (raw) {
        null -> null
        is LocalDateTime -> raw
        is OffsetDateTime -> raw.toLocalDateTime()
        is Instant -> LocalDateTime.ofInstant(raw, java.time.ZoneOffset.UTC)
        else -> null
    }
}

object LocalDateCodec : Codec<LocalDate> {
    override val sqlType: Class<*> = LocalDate::class.java
    override fun encode(value: LocalDate?): Any? = value
    override fun decode(raw: Any?): LocalDate? = raw as? LocalDate
}

object UuidCodec : Codec<UUID> {
    override val sqlType: Class<*> = UUID::class.java
    override fun encode(value: UUID?): Any? = value
    override fun decode(raw: Any?): UUID? = when (raw) {
        null -> null
        is UUID -> raw
        is String -> runCatching { UUID.fromString(raw) }.getOrNull()
        else -> null
    }
}

/**
 * Wraps a primitive codec to handle a Kotlin [@JvmInline value class] (or any
 * type with explicit wrap/unwrap functions). Eliminates the "what happens when
 * the inline class hits JDBC.setObject" trap from the original AggORM.
 *
 * Example:
 * ```
 * @JvmInline value class Email(val value: String)
 * val EmailCodec = ValueClassCodec(StringCodec, ::Email, Email::value)
 * ```
 */
class ValueClassCodec<V : Any, R : Any>(
    private val raw: Codec<R>,
    private val wrap: (R) -> V,
    private val unwrap: (V) -> R,
) : Codec<V> {
    override val sqlType: Class<*> = raw.sqlType
    override fun encode(value: V?): Any? = value?.let { raw.encode(unwrap(it)) }
    override fun decode(raw: Any?): V? = this.raw.decode(raw)?.let(wrap)
}

/** Convenience reader used by Table.fromRow. Centralizes null handling. */
fun <V> Codec<V>.read(row: Row, columnName: String): V? = decode(row.get(columnName))

/** ULID stored as 26-char Crockford `text`. Validation happens in Ulid.parse. */
val UlidCodec: Codec<Ulid> = ValueClassCodec(StringCodec, Ulid::parse, Ulid::value)

/** TSID stored as 13-char Crockford `text`. Validation happens in Tsid.parse. */
val TsidCodec: Codec<Tsid> = ValueClassCodec(StringCodec, Tsid::parse, Tsid::value)
