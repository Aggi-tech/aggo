package com.aggitech.aggo.schema

/**
 * Pre-built PostgreSQL CHECK expression factories for common domain type validations.
 *
 * Each factory returns a `(columnName: String) -> String` lambda consumed by
 * `Table.column(..., check = ...)`. The column name is automatically quoted to
 * match Aggo's identifier quoting convention ("name").
 *
 * Usage:
 * ```kotlin
 * object PayersTable : Table<Payer>("payers") {
 *     val id    = column("id",    IdCodec,    check = Checks.tsid())        { it.id }
 *     val email = column("email", EmailCodec, check = Checks.email())       { it.email }
 *     val name  = column("first_name", StringCodec, check = Checks.length(max = 100)) { it.firstName }
 * }
 *
 * // Generate Liquibase migration SQL:
 * PayersTable.addCheckConstraintsSql().forEach(::println)
 * // → ALTER TABLE "payers" ADD CONSTRAINT chk_payers_id CHECK (...);
 * // → ALTER TABLE "payers" ADD CONSTRAINT chk_payers_email CHECK (...);
 * ```
 */
object Checks {

    /**
     * Column must not be blank after trimming (trims ASCII spaces).
     * Useful for any required text column.
     */
    fun notBlank(): (String) -> String = { col ->
        "trim(\"$col\") <> ''"
    }

    /**
     * Column character length within [min]..[max] (both inclusive, both optional).
     * At least one of min/max must be provided.
     */
    fun length(min: Int? = null, max: Int? = null): (String) -> String {
        require(min != null || max != null) { "Checks.length: at least one of min/max must be specified" }
        return { col ->
            buildString {
                if (min != null) append("char_length(\"$col\") >= $min")
                if (min != null && max != null) append(" AND ")
                if (max != null) append("char_length(\"$col\") <= $max")
            }
        }
    }

    /**
     * Column must match the given POSIX regular expression (case-sensitive `~` operator).
     * Single quotes in the pattern are escaped automatically.
     */
    fun matches(pattern: String): (String) -> String = { col ->
        "\"$col\" ~ '${pattern.replace("'", "''")}'"
    }

    /**
     * Column must match the given POSIX regular expression (case-insensitive `~*` operator).
     */
    fun matchesIgnoreCase(pattern: String): (String) -> String = { col ->
        "\"$col\" ~* '${pattern.replace("'", "''")}'"
    }

    /**
     * Column value must be one of the given string literals.
     * Useful for enum-backed VARCHAR columns.
     *
     * ```kotlin
     * val status = column("status", UserStatusCodec,
     *     check = Checks.oneOf("ACTIVE", "DEACTIVATED", "PENDING")) { it.status.name }
     * ```
     */
    fun oneOf(vararg values: String): (String) -> String = { col ->
        val list = values.joinToString(", ") { v -> "'${v.replace("'", "''")}'" }
        "\"$col\" IN ($list)"
    }

    /**
     * Numeric column within [min]..[max] (inclusive, using BETWEEN).
     */
    fun between(min: Number, max: Number): (String) -> String = { col ->
        "\"$col\" BETWEEN $min AND $max"
    }

    /**
     * Column value must be strictly positive (> 0). For integer/numeric columns.
     */
    fun positive(): (String) -> String = { col -> "\"$col\" > 0" }

    /**
     * Column value must be non-negative (>= 0). For integer/numeric columns.
     */
    fun nonNegative(): (String) -> String = { col -> "\"$col\" >= 0" }

    /**
     * TSID (Time-Sorted ID) format constraint.
     * Matches exactly 13 uppercase Crockford base-32 characters as produced by
     * [com.aggitech.aggo.schema.ids.Tsid.generate].
     */
    fun tsid(): (String) -> String = { col ->
        "char_length(\"$col\") = 13 AND \"$col\" ~ '^[0-9A-HJKMNP-TV-Z]{13}$'"
    }

    /**
     * ULID format constraint — 26 chars, Crockford base-32 (no I/L/O/U).
     * Matches values produced by [com.aggitech.aggo.schema.ids.Ulid.generate].
     */
    fun ulid(): (String) -> String = { col ->
        "char_length(\"$col\") = 26 AND \"$col\" ~ '^[0-9A-HJKMNP-TV-Z]{26}$'"
    }

    /**
     * Loose email format constraint suitable for PostgreSQL.
     * Enforces `local@domain.tld` shape and a maximum length of 255 characters.
     * The application-layer [Email] value class enforces stricter rules; this
     * acts as a last-resort database guard against obviously corrupt data.
     */
    fun email(): (String) -> String = { col ->
        "\"$col\" ~ '^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$' AND char_length(\"$col\") <= 255"
    }

    /**
     * UUID format: 8-4-4-4-12 lowercase hex groups separated by hyphens.
     */
    fun uuid(): (String) -> String = { col ->
        "\"$col\" ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'"
    }

    /**
     * Combine multiple check expressions with AND.
     * Each sub-expression is wrapped in parentheses to avoid precedence surprises.
     *
     * ```kotlin
     * check = Checks.all(Checks.notBlank(), Checks.length(max = 100))
     * ```
     */
    fun all(vararg checks: (String) -> String): (String) -> String = { col ->
        checks.joinToString(" AND ") { check -> "(${check(col)})" }
    }

    /**
     * Combine multiple check expressions with OR.
     */
    fun any(vararg checks: (String) -> String): (String) -> String = { col ->
        checks.joinToString(" OR ") { check -> "(${check(col)})" }
    }
}
