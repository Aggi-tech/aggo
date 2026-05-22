package com.aggitech.aggo.dsl

import com.aggitech.aggo.query.ComparisonOp
import com.aggitech.aggo.query.Operand
import com.aggitech.aggo.query.Predicate
import com.aggitech.aggo.schema.Column

/**
 * Block-style predicate operators. Every operator works directly on a
 * [Column] descriptor, so the resulting [Predicate] tree carries the codec
 * needed to bind values safely — no reflection, no string column names.
 */

private fun <E, V> col(c: Column<E, V>): Operand = Operand.Col(c)
private fun <V> lit(value: V?, codec: com.aggitech.aggo.schema.Codec<V>): Operand =
    Operand.Literal(value, codec)

private fun <E, V> cmp(c: Column<E, V>, op: ComparisonOp, value: V?): Predicate =
    Predicate.Cmp(col(c), op, lit(value, c.codec))

infix fun <E, V> Column<E, V>.eq(value: V?): Predicate = cmp(this, ComparisonOp.EQ, value)
infix fun <E, V> Column<E, V>.ne(value: V?): Predicate = cmp(this, ComparisonOp.NE, value)
infix fun <E, V : Comparable<V>> Column<E, V>.gt(value: V): Predicate = cmp(this, ComparisonOp.GT, value)
infix fun <E, V : Comparable<V>> Column<E, V>.gte(value: V): Predicate = cmp(this, ComparisonOp.GTE, value)
infix fun <E, V : Comparable<V>> Column<E, V>.lt(value: V): Predicate = cmp(this, ComparisonOp.LT, value)
infix fun <E, V : Comparable<V>> Column<E, V>.lte(value: V): Predicate = cmp(this, ComparisonOp.LTE, value)

infix fun <E> Column<E, String>.like(pattern: String): Predicate =
    Predicate.Like(col(this), pattern, negated = false)

infix fun <E> Column<E, String>.notLike(pattern: String): Predicate =
    Predicate.Like(col(this), pattern, negated = true)

infix fun <E, V> Column<E, V>.inList(values: Collection<V?>): Predicate =
    Predicate.In(col(this), values.toList(), this.codec, negated = false)

infix fun <E, V> Column<E, V>.notInList(values: Collection<V?>): Predicate =
    Predicate.In(col(this), values.toList(), this.codec, negated = true)

fun <E, V> Column<E, V>.isNull(): Predicate = Predicate.IsNull(col(this), negated = false)
fun <E, V> Column<E, V>.isNotNull(): Predicate = Predicate.IsNull(col(this), negated = true)

fun <E, V : Comparable<V>> Column<E, V>.between(lower: V, upper: V): Predicate =
    Predicate.Between(col(this), lower, upper, this.codec)

infix fun Predicate.and(other: Predicate): Predicate = Predicate.And(this, other)
infix fun Predicate.or(other: Predicate): Predicate = Predicate.Or(this, other)
fun not(predicate: Predicate): Predicate = Predicate.Not(predicate)
