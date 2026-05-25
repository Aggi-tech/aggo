package com.aggitech.aggo.schema.ids

import java.security.SecureRandom
import java.time.Instant

/**
 * 128-bit ULID (Universally Unique Lexicographically Sortable Identifier).
 *
 * Layout (big-endian): 48 bits timestamp (ms since epoch) | 80 bits randomness.
 * Encoded as 26 chars Crockford base-32 — string order matches numeric order,
 * so it is safe to use as a Postgres `text` primary key in indexes.
 *
 * Built-in to keep the dependency tree clean (no third-party ULID lib).
 */
@JvmInline
value class Ulid private constructor(val value: String) : Comparable<Ulid> {

    init { require(value.length == 26) { "ULID must be 26 chars, got ${value.length}" } }

    override fun compareTo(other: Ulid): Int = value.compareTo(other.value)
    override fun toString(): String = value

    companion object {
        /** Strict Crockford-base-32 ULID matcher (26 chars, no I/L/O/U). */
        private val VALID = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

        /** Generate a fresh, monotonic ULID for the current instant. */
        fun generate(): Ulid = MonotonicUlid.next(Instant.now())

        /** Parse and validate a ULID string. Accepts lower-case input. */
        fun parse(s: String): Ulid {
            val upper = s.uppercase()
            require(VALID.matches(upper)) { "invalid ULID: $s" }
            return Ulid(upper)
        }

        /** Build a ULID from raw 16 bytes (mostly for tests / interop). */
        internal fun fromBytes(bytes: ByteArray): Ulid {
            require(bytes.size == 16) { "ULID must be 16 bytes, got ${bytes.size}" }
            return Ulid(Crockford.encode(bytes, width = 26))
        }
    }
}

/**
 * 64-bit TSID — 13 chars Crockford base-32. Same monotonicity contract as
 * [Ulid]; used by `Checks.tsid()` for legacy schemas.
 */
@JvmInline
value class Tsid private constructor(val value: String) : Comparable<Tsid> {

    init { require(value.length == 13) { "TSID must be 13 chars, got ${value.length}" } }

    override fun compareTo(other: Tsid): Int = value.compareTo(other.value)
    override fun toString(): String = value

    companion object {
        private val VALID = Regex("^[0-9A-HJKMNP-TV-Z]{13}$")

        fun generate(): Tsid = MonotonicTsid.next(Instant.now())

        fun parse(s: String): Tsid {
            val upper = s.uppercase()
            require(VALID.matches(upper)) { "invalid TSID: $s" }
            return Tsid(upper)
        }

        internal fun fromBytes(bytes: ByteArray): Tsid {
            require(bytes.size == 8) { "TSID must be 8 bytes, got ${bytes.size}" }
            return Tsid(Crockford.encode(bytes, width = 13))
        }
    }
}

/**
 * Monotonic ULID generator — spec-compliant: within the same millisecond,
 * randomness is incremented by 1 (treated as an 80-bit unsigned integer);
 * on overflow the timestamp moves forward one ms. Single `synchronized`
 * block — we issue one ID per insert, not in a tight loop.
 */
internal object MonotonicUlid {
    private val random = SecureRandom()

    private var lastTimestamp: Long = -1L
    // 80 bits of randomness held as 10 bytes, MSB-first.
    private val lastRandom = ByteArray(10)
    private val buffer = ByteArray(16)

    @Synchronized
    fun next(now: Instant): Ulid {
        var ts = now.toEpochMilli()
        if (ts <= lastTimestamp) {
            // Same ms (or clock drift) — increment the randomness; on overflow
            // advance the timestamp by 1 to keep lexicographic ordering.
            if (!incrementRandom(lastRandom)) {
                ts = lastTimestamp + 1
                random.nextBytes(lastRandom)
            }
        } else {
            random.nextBytes(lastRandom)
        }
        lastTimestamp = ts

        // 48-bit timestamp (big-endian) into bytes 0..5
        buffer[0] = (ts ushr 40).toByte()
        buffer[1] = (ts ushr 32).toByte()
        buffer[2] = (ts ushr 24).toByte()
        buffer[3] = (ts ushr 16).toByte()
        buffer[4] = (ts ushr 8).toByte()
        buffer[5] = ts.toByte()
        // 80-bit randomness into bytes 6..15
        System.arraycopy(lastRandom, 0, buffer, 6, 10)

        return Ulid.fromBytes(buffer)
    }

    /** Returns false on overflow (every byte was 0xFF). */
    private fun incrementRandom(bytes: ByteArray): Boolean {
        for (i in bytes.indices.reversed()) {
            val v = (bytes[i].toInt() and 0xFF) + 1
            bytes[i] = v.toByte()
            if (v <= 0xFF) return true
        }
        return false
    }
}

/**
 * Monotonic TSID generator — 42-bit timestamp (ms since epoch) | 22-bit
 * randomness. Same overflow / monotonicity behaviour as [MonotonicUlid].
 */
internal object MonotonicTsid {
    private val random = SecureRandom()
    private var lastTimestamp: Long = -1L
    private var lastRandomness: Int = 0
    private const val RANDOMNESS_MASK: Int = 0x3F_FFFF // 22 bits

    @Synchronized
    fun next(now: Instant): Tsid {
        var ts = now.toEpochMilli()
        if (ts <= lastTimestamp) {
            val incremented = (lastRandomness + 1) and RANDOMNESS_MASK
            if (incremented == 0) {
                ts = lastTimestamp + 1
                lastRandomness = random.nextInt() and RANDOMNESS_MASK
            } else {
                lastRandomness = incremented
            }
        } else {
            lastRandomness = random.nextInt() and RANDOMNESS_MASK
        }
        lastTimestamp = ts

        // Pack 42-bit ts | 22-bit rand into 64 bits.
        val tsPart = (ts and 0x3FF_FFFF_FFFFL) shl 22
        val packed = tsPart or lastRandomness.toLong()

        val bytes = ByteArray(8)
        for (i in 0..7) {
            bytes[i] = (packed ushr ((7 - i) * 8)).toByte()
        }
        return Tsid.fromBytes(bytes)
    }
}
