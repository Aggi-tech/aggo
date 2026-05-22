package com.aggitech.aggo.runtime

import com.aggitech.aggo.render.Bound
import org.slf4j.LoggerFactory

/**
 * Single slf4j logger for the whole library. The application's logging
 * pipeline (Quarkus/JBoss LogManager, Spring/Logback) routes it normally.
 *
 * Levels:
 *  - DEBUG: SQL only — safe for production, no parameter values leaked.
 *  - TRACE: SQL + parameters, with values matching [SENSITIVE_PATTERNS]
 *           replaced by `<redacted>`. Enable only when actively debugging.
 */
internal object QueryLog {

    private val log = LoggerFactory.getLogger("com.aggitech.aggo")

    /** Substrings (case-insensitive) that, when present in a Bound's codec or
     *  surrounding context, trigger redaction. The list is intentionally short
     *  — the right place to redact is at the application boundary, not here. */
    private val SENSITIVE_PATTERNS = listOf("password", "pwd", "secret", "token", "api_key")

    fun beforeExecute(sql: String, params: List<Bound>) {
        if (log.isTraceEnabled) {
            log.trace("aggo SQL: {} ; params={}", sql, redact(params))
        } else if (log.isDebugEnabled) {
            log.debug("aggo SQL: {}", sql)
        }
    }

    fun onError(sql: String, t: Throwable) {
        // Never include parameters in error logs — they may contain PII.
        log.error("aggo SQL failed: {}", sql, t)
    }

    private fun redact(params: List<Bound>): List<Any?> = params.map { bound ->
        val v = bound.value
        when {
            v == null -> null
            v is String && looksSensitive(v) -> "<redacted>"
            else -> v
        }
    }

    private fun looksSensitive(value: String): Boolean {
        val low = value.lowercase()
        return SENSITIVE_PATTERNS.any { low.contains(it) }
    }
}
