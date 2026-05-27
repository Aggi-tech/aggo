package com.aggitech.aggo.dsl

import com.aggitech.aggo.query.ComparisonOp
import com.aggitech.aggo.query.Operand
import com.aggitech.aggo.query.Predicate
import com.aggitech.aggo.schema.Column

/**
 * WHERE clause operators — used inside `where { … }` blocks in SELECT, UPDATE, DELETE,
 * and JOIN queries.
 *
 * Every operator is an infix extension on [Column], so you always reference the typed
 * column descriptor rather than a string name. The compiler enforces value types;
 * parameter binding is handled automatically via each column's [com.aggitech.aggo.schema.Codec].
 *
 * ## Comparison operators
 *
 * ```kotlin
 * where { UsersTable.email  eq  Email("alice@example.com") }  // =
 * where { UsersTable.email  ne  Email("alice@example.com") }  // <>
 * where { UsersTable.age    gt  18 }                          // >
 * where { UsersTable.age    gte 18 }                          // >=
 * where { UsersTable.age    lt  65 }                          // <
 * where { UsersTable.age    lte 65 }                          // <=
 * ```
 *
 * ## String matching
 *
 * ```kotlin
 * where { UsersTable.name like    "%alice%" }   // LIKE  (case-sensitive)
 * where { UsersTable.name notLike "%admin%" }   // NOT LIKE
 * ```
 *
 * ## Membership / range
 *
 * ```kotlin
 * where { UsersTable.status inList    listOf("ACTIVE", "PENDING") }  // IN (…)
 * where { UsersTable.status notInList listOf("BANNED", "DELETED") }  // NOT IN (…)
 * where { UsersTable.age.between(18, 65) }                           // BETWEEN 18 AND 65
 * ```
 *
 * ## Null checks
 *
 * ```kotlin
 * where { UsersTable.deletedAt.isNull() }     // IS NULL
 * where { UsersTable.deletedAt.isNotNull() }  // IS NOT NULL
 * ```
 *
 * ## Column-to-column comparison (for JOINs)
 *
 * ```kotlin
 * OrdersTable.leftJoin(UsersTable) { OrdersTable.userId eq UsersTable.id }
 * ```
 *
 * ## Logical composition
 *
 * ```kotlin
 * where { (UsersTable.active eq true) and (UsersTable.age gte 18) }
 * where { (UsersTable.role eq "ADMIN") or (UsersTable.role eq "SUPERUSER") }
 * where { not(UsersTable.active eq true) }
 * ```
 */

private fun <E, V> col(c: Column<E, V>): Operand = Operand.Col(c)
private fun <V> lit(value: V?, codec: com.aggitech.aggo.schema.Codec<V>): Operand =
    Operand.Literal(value, codec)

private fun <E, V> cmp(c: Column<E, V>, op: ComparisonOp, value: V?): Predicate =
    Predicate.Cmp(col(c), op, lit(value, c.codec))

infix fun <E, V> Column<E, V>.eq(value: V?): Predicate = cmp(this, ComparisonOp.EQ, value)
infix fun <L, R, V> Column<L, V>.eq(other: Column<R, V>): Predicate =
    Predicate.Cmp(col(this), ComparisonOp.EQ, col(other))

infix fun <E, V> Column<E, V>.ne(value: V?): Predicate = cmp(this, ComparisonOp.NE, value)
infix fun <L, R, V> Column<L, V>.ne(other: Column<R, V>): Predicate =
    Predicate.Cmp(col(this), ComparisonOp.NE, col(other))

infix fun <E, V : Comparable<V>> Column<E, V>.gt(value: V): Predicate = cmp(this, ComparisonOp.GT, value)
infix fun <E, V : Comparable<V>> Column<E, V>.gte(value: V): Predicate = cmp(this, ComparisonOp.GTE, value)
infix fun <E, V : Comparable<V>> Column<E, V>.lt(value: V): Predicate = cmp(this, ComparisonOp.LT, value)
infix fun <E, V : Comparable<V>> Column<E, V>.lte(value: V): Predicate = cmp(this, ComparisonOp.LTE, value)

infix fun <E> Column<E, String>.like(pattern: String): Predicate =
    Predicate.Like(col(this), pattern, negated = false)

infix fun <E> Column<E, String>.notLike(pattern: String): Predicate =
    Predicate.Like(col(this), pattern, negated = true)

infix fun <E> Column<E, String>.ilike(pattern: String): Predicate =
    Predicate.ILike(col(this), pattern, negated = false)

infix fun <E> Column<E, String>.notIlike(pattern: String): Predicate =
    Predicate.ILike(col(this), pattern, negated = true)

infix fun <E> Column<E, String>.matchesRegex(pattern: String): Predicate =
    Predicate.RegexMatch(col(this), pattern, caseInsensitive = false, negated = false)

infix fun <E> Column<E, String>.matchesRegexIgnoreCase(pattern: String): Predicate =
    Predicate.RegexMatch(col(this), pattern, caseInsensitive = true, negated = false)

infix fun <E> Column<E, String>.notMatchesRegex(pattern: String): Predicate =
    Predicate.RegexMatch(col(this), pattern, caseInsensitive = false, negated = true)

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
