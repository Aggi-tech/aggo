package com.aggitech.aggo.schema.ids

/**
 * Crockford base-32 alphabet — `0-9A-HJKMNP-TV-Z` (no `I`, `L`, `O`, `U`).
 *
 * Used by ULID / TSID generation. Kept package-internal so consumers go
 * through [Ulid] / [Tsid] instead of misusing the raw encoder.
 */
internal object Crockford {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /**
     * Encode [bytes] (big-endian) as a [width]-character Crockford base-32 string.
     *
     * Caller is responsible for sizing [width] consistently with `bytes.size * 8 / 5`
     * (rounded up). Throws if the produced output length disagrees with [width] —
     * a useful sanity check during development.
     */
    fun encode(bytes: ByteArray, width: Int): String {
        val sb = StringBuilder(width)
        var buffer = 0L
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toLong() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[((buffer ushr bits) and 0x1F).toInt()])
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET[((buffer shl (5 - bits)) and 0x1F).toInt()])
        }
        check(sb.length == width) { "Crockford encode width mismatch: ${sb.length} != $width" }
        return sb.toString()
    }
}
