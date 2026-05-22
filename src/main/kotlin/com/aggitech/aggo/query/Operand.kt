package com.aggitech.aggo.query

import com.aggitech.aggo.schema.Codec
import com.aggitech.aggo.schema.Column

/**
 * Anything that can appear on either side of a comparison in a WHERE clause.
 */
sealed interface Operand {
    /** A reference to a typed column. */
    data class Col<E, V>(val column: Column<E, V>) : Operand

    /** A literal value plus the codec that knows how to bind it. */
    data class Literal<V>(val value: V?, val codec: Codec<V>) : Operand
}
