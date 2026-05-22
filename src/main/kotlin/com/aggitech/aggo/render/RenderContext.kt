package com.aggitech.aggo.render

import com.aggitech.aggo.dialect.SqlDialect
import com.aggitech.aggo.schema.Codec

/**
 * Bound parameter — a value plus the codec that knows how to encode/null-bind it.
 * The renderer collects these in order so [com.aggitech.aggo.runtime.Binder]
 * can bind them to a Statement with the right type even when null.
 */
data class Bound(val value: Any?, val codec: Codec<*>)

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

    /** Append a bound value and return the placeholder to interpolate into SQL. */
    fun <V> bind(value: V?, codec: Codec<V>): String {
        collected += Bound(value, codec)
        return dialect.placeholder(collected.size)
    }
}
