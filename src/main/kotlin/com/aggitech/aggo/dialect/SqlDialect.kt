package com.aggitech.aggo.dialect

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
}

/** Strict identifier allowlist: letters, digits, underscore; must start with letter/underscore. */
internal val IDENTIFIER_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

internal const val MAX_IDENTIFIER_LENGTH = 63 // Postgres NAMEDATALEN-1

internal fun requireValidIdentifier(name: String) {
    require(name.isNotBlank()) { "identifier must not be blank" }
    require(name.length <= MAX_IDENTIFIER_LENGTH) {
        "identifier '$name' exceeds Postgres NAMEDATALEN ($MAX_IDENTIFIER_LENGTH)"
    }
    require(IDENTIFIER_REGEX.matches(name)) {
        "identifier '$name' is not a valid SQL identifier (only letters, digits, underscore; must start with letter or underscore)"
    }
}
