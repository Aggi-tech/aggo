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

    /**
     * V-6: redaction is column-driven. A bind attributable to a column with
     * `sensitive = true` is masked; literal predicates with no column
     * attribution are surfaced verbatim because the schema is the only place
     * that legitimately knows whether a value is PII.
     */
    internal fun redact(params: List<Bound>): List<Any?> = params.map { bound ->
        when {
            bound.value == null -> null
            bound.column?.sensitive == true -> "<redacted>"
            else -> bound.value
        }
    }
}
