package com.aggitech.aggo.runtime

import com.aggitech.aggo.render.Bound
import io.r2dbc.spi.Statement

/**
 * Bind parameters to an R2DBC [Statement] using the SQL type carried by each
 * codec. This fixes the two upstream bugs:
 *
 *  - `bindNull(i, Any.class)` from AggORM made Postgres reject "could not
 *    determine data type of parameter \$N". We use [Codec.sqlType] instead.
 *  - Inline / value-class values were silently `setObject(value)`'d; now
 *    [Codec.encode] unwraps them to a primitive the driver understands.
 */
internal object Binder {

    fun bind(statement: Statement, params: List<Bound>) {
        params.forEachIndexed { idx, bound ->
            bindOne(statement, idx, bound)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun bindOne(statement: Statement, index: Int, bound: Bound) {
        val codec = bound.codec as com.aggitech.aggo.schema.Codec<Any>
        val raw: Any? = if (bound.value == null) null else codec.encode(bound.value)
        if (raw == null) {
            statement.bindNull(index, codec.sqlType)
        } else {
            statement.bind(index, raw)
        }
    }
}
