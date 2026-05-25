package com.aggitech.aggo.render

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

/**
 * Accumulates parameters in render order and produces dialect-specific
 * placeholders. The single source of truth for "what comes out of [bind]".
 */
class RenderContext(val dialect: SqlDialect) {
    private val collected: MutableList<Bound> = mutableListOf()

    val params: List<Bound> get() = collected

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
