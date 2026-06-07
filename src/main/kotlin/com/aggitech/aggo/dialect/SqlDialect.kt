package com.aggitech.aggo.dialect

import com.aggitech.aggo.query.DatePart
import com.aggitech.aggo.query.IntervalUnit
import com.aggitech.aggo.schema.Column

/**
 * Bare minimum interface differentiating drivers. Postgres uses `$1, $2`;
 * MySQL uses `?`; identifier quoting differs too. Renderers go through this
 * interface — they never hard-code dialect characters.
 */
interface SqlDialect {
    /** Returns the placeholder for the [oneBasedIndex]-th parameter. */
    fun placeholder(oneBasedIndex: Int): String

    /** Quote (and validate) an identifier so it is safe to interpolate. */
    fun quoteIdentifier(name: String): String

    /**
     * Returns the fully-qualified table reference used in FROM / JOIN / UPDATE /
     * INSERT INTO / DELETE FROM clauses.
     *
     * Default: bare quoted identifier.
     */
    fun qualifyTableName(tableName: String): String = quoteIdentifier(tableName)

    /** Render dialect-specific LIMIT/OFFSET syntax. Empty string means no clause. */
    fun renderPagination(limit: Int?, offset: Int?): String

    /** Render a case-insensitive LIKE predicate for this dialect. */
    fun renderLikeIgnoreCase(operand: String, pattern: String, negated: Boolean): String

    /** Render a regex predicate for this dialect. */
    fun renderRegexMatch(
        operand: String,
        pattern: String,
        caseInsensitive: Boolean,
        negated: Boolean,
    ): String

    /** Choose the dialect-specific strategy used when an INSERT returns PK columns. */
    fun insertReturnStrategy(primaryKeyColumns: List<Column<*, *>>): InsertReturnStrategy

    /**
     * Render `EXTRACT(field FROM source)` — pulling a date/time subfield
     * (year, month, hour, day-of-week, ...) out of [source].
     *
     * `EXTRACT(... FROM ...)` is ANSI SQL and Postgres, MySQL, and Oracle all
     * accept the same syntax for the common [DatePart]s, so the default
     * implementation is portable. Override only if a dialect needs different
     * keywords for a particular field.
     */
    fun renderExtract(field: DatePart, source: String): String = "EXTRACT(${field.sql} FROM $source)"

    /**
     * Render a date/time truncation — "round [source] down to the precision
     * named by [field]" (`DAY`, `MONTH`, `HOUR`, ...).
     *
     * Unlike `EXTRACT`, there is no portable ANSI syntax for this: Postgres
     * has `date_trunc('day', source)`, MySQL needs a `DATE_FORMAT`/cast
     * combination, and Oracle uses `TRUNC(source, 'DD')`. Each dialect must
     * provide its own rendering; throw [UnsupportedOperationException] for
     * any [DatePart] the underlying database cannot truncate to.
     */
    fun renderDateTrunc(field: DatePart, source: String): String

    /**
     * Render a typed `quantity * unit` interval, where [quantityPlaceholder]
     * is an already-bound parameter placeholder (e.g. `$1`, `?`, `:1`) for an
     * `Int` quantity and [unit] is a closed [IntervalUnit].
     *
     * There is no portable interval literal syntax across databases —
     * Postgres accepts `$1 * INTERVAL '1 day'`, MySQL needs `INTERVAL ? DAY`,
     * Oracle needs `NUMTODSINTERVAL(?, 'DAY')` / `NUMTOYMINTERVAL(?, 'MONTH')`.
     * Each dialect renders the form its database understands; the quantity is
     * always a bound parameter, never spliced into the SQL string.
     */
    fun renderInterval(quantityPlaceholder: String, unit: IntervalUnit): String
}

/** Strict identifier allowlist: letters, digits, underscore; must start with letter/underscore. */
val IDENTIFIER_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

const val MAX_IDENTIFIER_LENGTH = 63 // Postgres NAMEDATALEN-1

/**
 * Validate that [name] is safe to interpolate into a SQL statement. Throws
 * [IllegalArgumentException] otherwise. Public so the schema layer can fail
 * fast at [com.aggitech.aggo.schema.Table] / column construction, not at
 * render time (V-2).
 */
fun requireValidIdentifier(name: String) {
    require(name.isNotBlank()) { "identifier must not be blank" }
    require(name.length <= MAX_IDENTIFIER_LENGTH) {
        "identifier '$name' exceeds Postgres NAMEDATALEN ($MAX_IDENTIFIER_LENGTH)"
    }
    require(IDENTIFIER_REGEX.matches(name)) {
        "identifier '$name' is not a valid SQL identifier (only letters, digits, underscore; must start with letter or underscore)"
    }
}
