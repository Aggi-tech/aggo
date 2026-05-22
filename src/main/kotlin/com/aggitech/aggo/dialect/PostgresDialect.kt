package com.aggitech.aggo.dialect

object PostgresDialect : SqlDialect {
    override fun placeholder(oneBasedIndex: Int): String = "\$$oneBasedIndex"

    override fun quoteIdentifier(name: String): String {
        requireValidIdentifier(name)
        // The regex above already excludes the double-quote character, so the
        // doubling is defense-in-depth in case the validator is ever loosened.
        val escaped = name.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
