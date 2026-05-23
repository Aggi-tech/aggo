package com.aggitech.aggo.runtime

import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata

internal class PositionalRow(
    private val delegate: Row,
    private val nameToIndex: Map<String, Int>,
) : Row {
    override fun getMetadata(): RowMetadata = delegate.metadata

    override fun <T : Any?> get(index: Int, type: Class<T>): T? =
        delegate.get(index, type)

    override fun <T : Any?> get(name: String, type: Class<T>): T? {
        val index = nameToIndex[name] ?: error("Column '$name' is not available in this row view")
        return delegate.get(index, type)
    }
}
