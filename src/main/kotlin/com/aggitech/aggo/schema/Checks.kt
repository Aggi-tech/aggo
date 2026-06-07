package com.aggitech.aggo.schema

/**
 * Pre-built PostgreSQL CHECK expression factories for common domain type validations.
 *
 * Each factory returns a [CheckConstraint] consumed by `Table.column(..., check = ...)` or
 * chained via `ColumnBuilder.check(...)` / `ColumnBuilder.checks(...)`.
 * Because [CheckConstraint] implements `(String) -> String`, existing call-sites that accept
 * a plain lambda continue to compile without changes.
 *
 * The optional [key] parameter is the stable application-facing error key returned by
 * `constraintErrorMap` when the database reports this constraint violation.
 * The optional [name] parameter overrides the auto-generated constraint name.
 *
 * Usage:
 * ```kotlin
 * // Single check (old style — unchanged)
 * val email = column("email", EmailCodec, check = Checks.email()) { it.email }
 *
 * // Multiple checks with per-check error keys (new style)
 * val id = tsid("id")
 *     .checks(
 *         Checks.notBlank(key = "id.blank"),
 *         Checks.tsid(key = "id.format"),
 *     )
 *     .primaryKey()
 *     .required { it.id }
 * ```
 */
object Checks {

    fun notBlank(key: String? = null, name: String? = null): CheckConstraint =
        CheckConstraint({ col -> "trim(\"$col\") <> ''" }, name, key)

    /**
     * Column character length within [min]..[max] (both inclusive, both optional).
     * At least one of min/max must be provided.
     */
    fun length(min: Int? = null, max: Int? = null, key: String? = null, name: String? = null): CheckConstraint {
        require(min != null || max != null) { "Checks.length: at least one of min/max must be specified" }
        return CheckConstraint({ col ->
            buildString {
                if (min != null) append("char_length(\"$col\") >= $min")
                if (min != null && max != null) append(" AND ")
                if (max != null) append("char_length(\"$col\") <= $max")
            }
        }, name, key)
    }

    /**
     * Column must match the given POSIX regular expression (case-sensitive `~` operator).
     * Single quotes in the pattern are escaped automatically.
     */
    fun matches(pattern: String, key: String? = null, name: String? = null): CheckConstraint =
        CheckConstraint({ col -> "\"$col\" ~ '${pattern.replace("'", "''")}'" }, name, key)

    /**
     * Column must match the given POSIX regular expression (case-insensitive `~*` operator).
     */
    fun matchesIgnoreCase(pattern: String, key: String? = null, name: String? = null): CheckConstraint =
        CheckConstraint({ col -> "\"$col\" ~* '${pattern.replace("'", "''")}'" }, name, key)

    /**
     * Column value must be one of the given string literals.
     * Useful for enum-backed VARCHAR columns.
     *
     * ```kotlin
     * val status = column("status", UserStatusCodec,
     *     check = Checks.oneOf("ACTIVE", "DEACTIVATED", "PENDING")) { it.status.name }
     * ```
     */
    fun oneOf(vararg values: String, key: String? = null, name: String? = null): CheckConstraint {
        val list = values.joinToString(", ") { v -> "'${v.replace("'", "''")}'" }
        return CheckConstraint({ col -> "\"$col\" IN ($list)" }, name, key)
    }

    /**
     * Numeric column within [min]..[max] (inclusive, using BETWEEN).
     */
    fun between(min: Number, max: Number, key: String? = null, name: String? = null): CheckConstraint =
        CheckConstraint({ col -> "\"$col\" BETWEEN $min AND $max" }, name, key)

    fun positive(key: String? = null, name: String? = null): CheckConstraint =
        CheckConstraint({ col -> "\"$col\" > 0" }, name, key)

    fun nonNegative(key: String? = null, name: String? = null): CheckConstraint =
        CheckConstraint({ col -> "\"$col\" >= 0" }, name, key)

    /**
     * TSID (Time-Sorted ID) format constraint.
     * Matches exactly 13 uppercase Crockford base-32 characters as produced by
     * [com.aggitech.aggo.schema.ids.Tsid.generate].
     */
    fun tsid(key: String? = null, name: String? = null): CheckConstraint =
        CheckConstraint(
            { col -> "char_length(\"$col\") = 13 AND \"$col\" ~ '^[0-9A-HJKMNP-TV-Z]{13}$'" },
            name, key,
        )

    /**
     * ULID format constraint — 26 chars, Crockford base-32 (no I/L/O/U).
     * Matches values produced by [com.aggitech.aggo.schema.ids.Ulid.generate].
     */
    fun ulid(key: String? = null, name: String? = null): CheckConstraint =
        CheckConstraint(
            { col -> "char_length(\"$col\") = 26 AND \"$col\" ~ '^[0-9A-HJKMNP-TV-Z]{26}$'" },
            name, key,
        )

    /**
     * Loose email format constraint suitable for PostgreSQL.
     * Enforces `local@domain.tld` shape and a maximum length of 255 characters.
     */
    fun email(key: String? = null, name: String? = null): CheckConstraint =
        CheckConstraint(
            { col -> "\"$col\" ~ '^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$' AND char_length(\"$col\") <= 255" },
            name, key,
        )

    /**
     * UUID format: 8-4-4-4-12 lowercase hex groups separated by hyphens.
     */
    fun uuid(key: String? = null, name: String? = null): CheckConstraint =
        CheckConstraint(
            { col -> "\"$col\" ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'" },
            name, key,
        )

    /**
     * Combine multiple check expressions with AND. Each sub-expression is wrapped in
     * parentheses to avoid precedence surprises.
     *
     * ```kotlin
     * check = Checks.all(Checks.notBlank(), Checks.length(max = 100))
     * ```
     *
     * Trailing [key] and [name] apply to the combined constraint, not to the individual checks.
     * For per-check keys, use `ColumnBuilder.checks(...)` instead.
     */
    fun all(vararg checks: (String) -> String, key: String? = null, name: String? = null): CheckConstraint =
        CheckConstraint(
            { col -> checks.joinToString(" AND ") { check -> "(${check(col)})" } },
            name, key,
        )

    /**
     * Combine multiple check expressions with OR.
     */
    fun any(vararg checks: (String) -> String, key: String? = null, name: String? = null): CheckConstraint =
        CheckConstraint(
            { col -> checks.joinToString(" OR ") { check -> "(${check(col)})" } },
            name, key,
        )
}
