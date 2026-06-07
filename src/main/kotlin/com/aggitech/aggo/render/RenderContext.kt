package com.aggitech.aggo.render

import com.aggitech.aggo.dialect.InsertReturnStrategy
import com.aggitech.aggo.dialect.SqlDialect
import com.aggitech.aggo.schema.Codec
import com.aggitech.aggo.schema.Column

/**
 * Bound parameter — a value plus the codec that knows how to encode/null-bind it.
 * The renderer collects these in order so [com.aggitech.aggo.runtime.Binder]
 * can bind them to a Statement with the right type even when null.
 *
 * [column] carries the originating column when the bind site is clearly attributable
 * (UPDATE/INSERT assignment or `column = literal` predicate). [QueryLog] consults
 * it to honor [com.aggitech.aggo.schema.Column.sensitive] (V-6).
 */
data class Bound(
    val value: Any?,
    val codec: Codec<*>,
    val column: Column<*, *>? = null,
)

/** Final output of a renderer: the SQL string and its bound parameters, in order. */
data class RenderedSql(val sql: String, val params: List<Bound>) {
    override fun toString(): String = "RenderedSql(sql=$sql, params.size=${params.size})"
}

/** Rendered INSERT plus dialect-specific primary-key return strategy. */
data class RenderedInsert(
    val sql: String,
    val params: List<Bound>,
    val returnStrategy: InsertReturnStrategy?,
) {
    fun asRenderedSql(): RenderedSql = RenderedSql(sql, params)
}

/**
 * Accumulates parameters in render order and produces dialect-specific
 * placeholders. The single source of truth for "what comes out of [bind]".
 */
class RenderContext(val dialect: SqlDialect, private val tableAliases: Map<String, String> = emptyMap()) {
    private val collected: MutableList<Bound> = mutableListOf()

    val params: List<Bound> get() = collected

    /**
     * Render a fully-qualified column reference, honouring any table alias
     * registered for [column]'s table (e.g. `select(People, alias = "p")`
     * renders `"p"."id"` instead of `"people"."id"`).
     *
     * The single qualification choke-point — [PredicateRenderer] and the
     * SELECT renderers call this instead of building `qualifier.column`
     * strings by hand, so alias support never needs duplicating.
     */
    fun qualifyColumn(column: Column<*, *>): String {
        val tableName = column.table.name
        val qualifier = tableAliases[tableName]?.let { dialect.quoteIdentifier(it) }
            ?: dialect.qualifyTableName(tableName)
        return "$qualifier.${dialect.quoteIdentifier(column.name)}"
    }

    /**
     * Append a bound value and return the placeholder. [column] should be set
     * whenever the bind belongs to a specific column (assignments, `col = lit`
     * predicates) so V-6 redaction can decide based on schema metadata.
     */
    fun <V> bind(value: V?, codec: Codec<V>, column: Column<*, *>? = null): String {
        collected += Bound(value, codec, column)
        return dialect.placeholder(collected.size)
    }
}
