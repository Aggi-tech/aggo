package com.aggitech.aggo.query

import com.aggitech.aggo.schema.Codec

enum class ComparisonOp(val symbol: String) {
    EQ("="), NE("<>"), GT(">"), GTE(">="), LT("<"), LTE("<=")
}

/** Logical / comparison tree. Pure data, no execution side-effects. */
sealed interface Predicate {
    data class Cmp(val left: Operand, val op: ComparisonOp, val right: Operand) : Predicate
    data class Like(val operand: Operand, val pattern: String, val negated: Boolean = false) : Predicate
    data class In<V>(
        val operand: Operand,
        val values: List<V?>,
        val codec: Codec<V>,
        val negated: Boolean = false,
    ) : Predicate {
        init {
            // V-8: Postgres caps a statement at 65 535 parameters; bail out at
            // build time with a clear message instead of letting the driver
            // throw a 'too many parameters' deep inside the network layer.
            require(values.size <= MAX_IN_SIZE) {
                "IN list has ${values.size} elements, exceeds MAX_IN_SIZE ($MAX_IN_SIZE)"
            }
        }
        companion object { const val MAX_IN_SIZE: Int = 32_000 }
    }
    data class Between<V>(val operand: Operand, val lower: V, val upper: V, val codec: Codec<V>) : Predicate
    data class IsNull(val operand: Operand, val negated: Boolean = false) : Predicate
    data class And(val left: Predicate, val right: Predicate) : Predicate
    data class Or(val left: Predicate, val right: Predicate) : Predicate
    data class Not(val inner: Predicate) : Predicate
}
