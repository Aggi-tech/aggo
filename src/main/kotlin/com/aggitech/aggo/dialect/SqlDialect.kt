package com.aggitech.aggo.dialect

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
