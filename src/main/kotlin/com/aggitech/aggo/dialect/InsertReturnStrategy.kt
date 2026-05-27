package com.aggitech.aggo.dialect

/**
 * Dialect-specific execution strategy for INSERT ... returning primary keys.
 */
sealed interface InsertReturnStrategy {
    /** PostgreSQL-style strategy: append a RETURNING clause to the INSERT. */
    data class AppendClause(val clause: String) : InsertReturnStrategy

    /** MySQL strategy: run this SELECT on the same connection after the INSERT. */
    data class PostInsertSelect(val sql: String) : InsertReturnStrategy

    /** Oracle strategy: append RETURNING ... INTO and bind OUT parameters. */
    data class ReturningInto(
        val clause: String,
        val outParams: List<OutParam>,
    ) : InsertReturnStrategy

    data class OutParam(
        val bindName: String,
        val sqlType: Class<*>,
    )
}
