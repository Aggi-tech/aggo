package com.aggitech.aggo.dsl

import com.aggitech.aggo.query.ArithmeticOp
import com.aggitech.aggo.query.ComparisonOp
import com.aggitech.aggo.query.Expr
import com.aggitech.aggo.query.Operand
import com.aggitech.aggo.query.Predicate
import com.aggitech.aggo.schema.BigDecimalCodec
import com.aggitech.aggo.schema.Codec
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.DoubleCodec
import com.aggitech.aggo.schema.IntCodec
import com.aggitech.aggo.schema.LongCodec
import com.aggitech.aggo.schema.StringCodec

// ─── Column → Expr promotion ──────────────────────────────────────────────────

/**
 * Promotes this column into a bare [Expr], useful when you need to pass a plain
 * column reference to a function that expects an [Expr] (e.g. [coalesce]).
 *
 * ```kotlin
 * coalesce(Users.nickname.asExpr(), Users.fullName.asExpr(), Expr.of("anonymous", StringCodec))
 * ```
 */
fun <E, V> Column<E, V>.asExpr(): Expr<V> = Expr(Operand.Col(this), codec)

// ─── Numeric arithmetic: Column op Column ─────────────────────────────────────

/**
 * Column arithmetic operators — produce an [Expr] that renders as
 * `("table"."col" op other)` in SQL.
 *
 * Both sides must share the same value type [V]. For mixed-type arithmetic
 * (e.g. Int + Long) use explicit casts at the Postgres level.
 *
 * ```kotlin
 * where { (Orders.price + Orders.tax) gt BigDecimal("100") }
 * where { (Products.stock - Products.reserved) gt 0 }
 * ```
 */
operator fun <E, V : Number> Column<E, V>.plus(other: Column<*, V>): Expr<V> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.ADD, Operand.Col(other)), codec)

operator fun <E, V : Number> Column<E, V>.minus(other: Column<*, V>): Expr<V> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.SUB, Operand.Col(other)), codec)

operator fun <E, V : Number> Column<E, V>.times(other: Column<*, V>): Expr<V> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.MUL, Operand.Col(other)), codec)

operator fun <E, V : Number> Column<E, V>.div(other: Column<*, V>): Expr<V> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.DIV, Operand.Col(other)), codec)

operator fun <E, V : Number> Column<E, V>.rem(other: Column<*, V>): Expr<V> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.MOD, Operand.Col(other)), codec)

// ─── Numeric arithmetic: Column op literal ────────────────────────────────────

/**
 * Arithmetic between a column and a literal value.
 *
 * ```kotlin
 * where { (Products.price * 2) lt BigDecimal("50") }
 * ```
 */
operator fun <E, V : Number> Column<E, V>.plus(value: V): Expr<V> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.ADD, Operand.Literal(value, codec)), codec)

operator fun <E, V : Number> Column<E, V>.minus(value: V): Expr<V> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.SUB, Operand.Literal(value, codec)), codec)

operator fun <E, V : Number> Column<E, V>.times(value: V): Expr<V> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.MUL, Operand.Literal(value, codec)), codec)

operator fun <E, V : Number> Column<E, V>.div(value: V): Expr<V> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.DIV, Operand.Literal(value, codec)), codec)

operator fun <E, V : Number> Column<E, V>.rem(value: V): Expr<V> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.MOD, Operand.Literal(value, codec)), codec)

// ─── Numeric arithmetic: Column op Expr ───────────────────────────────────────

@JvmName("numColPlusExpr")
operator fun <E, V : Number> Column<E, V>.plus(expr: Expr<V>): Expr<V> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.ADD, expr.operand), codec)

operator fun <E, V : Number> Column<E, V>.minus(expr: Expr<V>): Expr<V> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.SUB, expr.operand), codec)

operator fun <E, V : Number> Column<E, V>.times(expr: Expr<V>): Expr<V> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.MUL, expr.operand), codec)

operator fun <E, V : Number> Column<E, V>.div(expr: Expr<V>): Expr<V> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.DIV, expr.operand), codec)

// ─── Numeric arithmetic: Expr op Expr / Column / literal ──────────────────────

/**
 * Arithmetic operators on [Expr] values for chaining multiple operations.
 *
 * ```kotlin
 * val discount = Products.price * 0.9
 * val netPrice = discount - Products.discount
 * where { netPrice gt BigDecimal("0") }
 * ```
 */
@JvmName("numExprPlusExpr")
operator fun <V : Number> Expr<V>.plus(other: Expr<V>): Expr<V> =
    Expr(Operand.BinaryExpr(operand, ArithmeticOp.ADD, other.operand), codec)

operator fun <V : Number> Expr<V>.minus(other: Expr<V>): Expr<V> =
    Expr(Operand.BinaryExpr(operand, ArithmeticOp.SUB, other.operand), codec)

operator fun <V : Number> Expr<V>.times(other: Expr<V>): Expr<V> =
    Expr(Operand.BinaryExpr(operand, ArithmeticOp.MUL, other.operand), codec)

operator fun <V : Number> Expr<V>.div(other: Expr<V>): Expr<V> =
    Expr(Operand.BinaryExpr(operand, ArithmeticOp.DIV, other.operand), codec)

operator fun <V : Number> Expr<V>.rem(other: Expr<V>): Expr<V> =
    Expr(Operand.BinaryExpr(operand, ArithmeticOp.MOD, other.operand), codec)

@JvmName("numExprPlusCol")
operator fun <V : Number> Expr<V>.plus(column: Column<*, V>): Expr<V> =
    Expr(Operand.BinaryExpr(operand, ArithmeticOp.ADD, Operand.Col(column)), codec)

operator fun <V : Number> Expr<V>.minus(column: Column<*, V>): Expr<V> =
    Expr(Operand.BinaryExpr(operand, ArithmeticOp.SUB, Operand.Col(column)), codec)

operator fun <V : Number> Expr<V>.times(column: Column<*, V>): Expr<V> =
    Expr(Operand.BinaryExpr(operand, ArithmeticOp.MUL, Operand.Col(column)), codec)

operator fun <V : Number> Expr<V>.div(column: Column<*, V>): Expr<V> =
    Expr(Operand.BinaryExpr(operand, ArithmeticOp.DIV, Operand.Col(column)), codec)

operator fun <V : Number> Expr<V>.plus(value: V): Expr<V> =
    Expr(Operand.BinaryExpr(operand, ArithmeticOp.ADD, Operand.Literal(value, codec)), codec)

operator fun <V : Number> Expr<V>.minus(value: V): Expr<V> =
    Expr(Operand.BinaryExpr(operand, ArithmeticOp.SUB, Operand.Literal(value, codec)), codec)

operator fun <V : Number> Expr<V>.times(value: V): Expr<V> =
    Expr(Operand.BinaryExpr(operand, ArithmeticOp.MUL, Operand.Literal(value, codec)), codec)

operator fun <V : Number> Expr<V>.div(value: V): Expr<V> =
    Expr(Operand.BinaryExpr(operand, ArithmeticOp.DIV, Operand.Literal(value, codec)), codec)

// ─── String concatenation (SQL: ||) ───────────────────────────────────────────

/**
 * String concatenation using the SQL `||` operator, accessed via Kotlin's `+`.
 *
 * ```kotlin
 * // Combine two string columns
 * where { (Users.firstName + " " + Users.lastName) eq "Alice Smith" }
 *
 * // Prepend/append a literal
 * select(Users) { where { (Users.prefix + Users.code) like "SVC-%" } }
 * ```
 */
@JvmName("strColPlusCol")
operator fun <E> Column<E, String>.plus(other: Column<*, String>): Expr<String> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.CONCAT, Operand.Col(other)), StringCodec)

operator fun <E> Column<E, String>.plus(value: String): Expr<String> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.CONCAT, Operand.Literal(value, StringCodec)), StringCodec)

@JvmName("strColPlusExpr")
operator fun <E> Column<E, String>.plus(expr: Expr<String>): Expr<String> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.CONCAT, expr.operand), StringCodec)

@JvmName("strExprPlusExpr")
operator fun Expr<String>.plus(other: Expr<String>): Expr<String> =
    Expr(Operand.BinaryExpr(operand, ArithmeticOp.CONCAT, other.operand), StringCodec)

@JvmName("strExprPlusCol")
operator fun Expr<String>.plus(column: Column<*, String>): Expr<String> =
    Expr(Operand.BinaryExpr(operand, ArithmeticOp.CONCAT, Operand.Col(column)), StringCodec)

operator fun Expr<String>.plus(value: String): Expr<String> =
    Expr(Operand.BinaryExpr(operand, ArithmeticOp.CONCAT, Operand.Literal(value, StringCodec)), StringCodec)

// ─── WHERE / HAVING comparison operators on Expr ──────────────────────────────

/**
 * Comparison operators on [Expr] values, for use in `where { }` and `having { }` blocks.
 *
 * These mirror the column-level operators in [Operators]: `eq`, `ne`, `gt`, `gte`, `lt`, `lte`.
 *
 * ```kotlin
 * // Aggregate in HAVING
 * having { count(Orders.id) gt 5L }
 *
 * // Arithmetic in WHERE
 * where { (Items.price - Items.discount) gte BigDecimal("10") }
 *
 * // Function result in WHERE
 * where { lower(Users.email) eq "alice@example.com" }
 * ```
 */
infix fun <V> Expr<V>.eq(value: V?): Predicate =
    Predicate.Cmp(operand, ComparisonOp.EQ, Operand.Literal(value, codec))

infix fun <V> Expr<V>.ne(value: V?): Predicate =
    Predicate.Cmp(operand, ComparisonOp.NE, Operand.Literal(value, codec))

infix fun <V : Comparable<V>> Expr<V>.gt(value: V): Predicate =
    Predicate.Cmp(operand, ComparisonOp.GT, Operand.Literal(value, codec))

infix fun <V : Comparable<V>> Expr<V>.gte(value: V): Predicate =
    Predicate.Cmp(operand, ComparisonOp.GTE, Operand.Literal(value, codec))

infix fun <V : Comparable<V>> Expr<V>.lt(value: V): Predicate =
    Predicate.Cmp(operand, ComparisonOp.LT, Operand.Literal(value, codec))

infix fun <V : Comparable<V>> Expr<V>.lte(value: V): Predicate =
    Predicate.Cmp(operand, ComparisonOp.LTE, Operand.Literal(value, codec))

/** `BETWEEN lower AND upper` on an expression result. */
fun <V : Comparable<V>> Expr<V>.between(lower: V, upper: V): Predicate =
    Predicate.Between(operand, lower, upper, codec)

/** `IS NULL` on an expression result (e.g. `coalesce(col, other).isNull()`). */
fun <V> Expr<V>.isNull(): Predicate = Predicate.IsNull(operand, negated = false)

/** `IS NOT NULL` on an expression result. */
fun <V> Expr<V>.isNotNull(): Predicate = Predicate.IsNull(operand, negated = true)

// ─── Cross-expression comparisons (Expr vs Expr, Column vs Expr) ──────────────

/**
 * Compare two expression results directly — e.g. `dateTrunc(...) eq dateTrunc(...)`,
 * or a column shifted by an interval against [com.aggitech.aggo.dsl.now]:
 *
 * ```kotlin
 * where { Sessions.expiresAt lt now() }
 * where { (Orders.shippedAt) gt (Orders.placedAt) }
 * where { Orders.placedAt.plusInterval(30, IntervalUnit.DAY) lt now() }
 * ```
 */
infix fun <V> Expr<V>.eq(other: Expr<V>): Predicate = Predicate.Cmp(operand, ComparisonOp.EQ, other.operand)
infix fun <V> Expr<V>.ne(other: Expr<V>): Predicate = Predicate.Cmp(operand, ComparisonOp.NE, other.operand)
infix fun <V : Comparable<V>> Expr<V>.gt(other: Expr<V>): Predicate = Predicate.Cmp(operand, ComparisonOp.GT, other.operand)
infix fun <V : Comparable<V>> Expr<V>.gte(other: Expr<V>): Predicate = Predicate.Cmp(operand, ComparisonOp.GTE, other.operand)
infix fun <V : Comparable<V>> Expr<V>.lt(other: Expr<V>): Predicate = Predicate.Cmp(operand, ComparisonOp.LT, other.operand)
infix fun <V : Comparable<V>> Expr<V>.lte(other: Expr<V>): Predicate = Predicate.Cmp(operand, ComparisonOp.LTE, other.operand)

/** Compare a bare column directly against an expression result, e.g. `Sessions.expiresAt lt now()`. */
infix fun <E, V> Column<E, V>.eq(expr: Expr<V>): Predicate = Predicate.Cmp(Operand.Col(this), ComparisonOp.EQ, expr.operand)
infix fun <E, V> Column<E, V>.ne(expr: Expr<V>): Predicate = Predicate.Cmp(Operand.Col(this), ComparisonOp.NE, expr.operand)
infix fun <E, V : Comparable<V>> Column<E, V>.gt(expr: Expr<V>): Predicate = Predicate.Cmp(Operand.Col(this), ComparisonOp.GT, expr.operand)
infix fun <E, V : Comparable<V>> Column<E, V>.gte(expr: Expr<V>): Predicate = Predicate.Cmp(Operand.Col(this), ComparisonOp.GTE, expr.operand)
infix fun <E, V : Comparable<V>> Column<E, V>.lt(expr: Expr<V>): Predicate = Predicate.Cmp(Operand.Col(this), ComparisonOp.LT, expr.operand)
infix fun <E, V : Comparable<V>> Column<E, V>.lte(expr: Expr<V>): Predicate = Predicate.Cmp(Operand.Col(this), ComparisonOp.LTE, expr.operand)

// ─── Aggregate functions ───────────────────────────────────────────────────────

/**
 * `SUM(column)` — sum of all non-null values. Result type matches the column type.
 *
 * ```kotlin
 * val total = sum(Orders.amount) `as` "total"
 * aggregate(Orders) { project(total); groupBy(Orders.customerId) }
 * ```
 */
fun <E, V : Number> sum(column: Column<E, V>): Expr<V> =
    Expr(Operand.FunctionCall("SUM", listOf(Operand.Col(column))), column.codec)

/** `SUM(DISTINCT column)` — sum of distinct non-null values. */
fun <E, V : Number> sumDistinct(column: Column<E, V>): Expr<V> =
    Expr(Operand.FunctionCall("SUM", listOf(Operand.Col(column)), distinct = true), column.codec)

/**
 * `COUNT(column)` — count of non-null values in the column.
 *
 * For `COUNT(*)` use [countStar].
 *
 * ```kotlin
 * val cnt = count(Users.id) `as` "cnt"
 * ```
 */
fun <E> count(column: Column<E, *>): Expr<Long> =
    Expr(Operand.FunctionCall("COUNT", listOf(Operand.Col(column))), LongCodec)

/** `COUNT(DISTINCT column)` — count of distinct non-null values. */
fun <E> countDistinct(column: Column<E, *>): Expr<Long> =
    Expr(Operand.FunctionCall("COUNT", listOf(Operand.Col(column)), distinct = true), LongCodec)

/**
 * `COUNT(*)` — total number of rows, including those with nulls.
 *
 * ```kotlin
 * val total = countStar() `as` "total"
 * aggregate(Products) { project(total) }
 * ```
 */
fun countStar(): Expr<Long> =
    Expr(Operand.FunctionCall("COUNT", listOf(Operand.Star)), LongCodec)

/**
 * `MAX(column)` — maximum value. Result type matches the column type.
 *
 * ```kotlin
 * val latest = max(Events.occurredAt) `as` "latest"
 * ```
 */
fun <E, V : Comparable<V>> max(column: Column<E, V>): Expr<V> =
    Expr(Operand.FunctionCall("MAX", listOf(Operand.Col(column))), column.codec)

/**
 * `MIN(column)` — minimum value. Result type matches the column type.
 *
 * ```kotlin
 * val earliest = min(Events.occurredAt) `as` "earliest"
 * ```
 */
fun <E, V : Comparable<V>> min(column: Column<E, V>): Expr<V> =
    Expr(Operand.FunctionCall("MIN", listOf(Operand.Col(column))), column.codec)

/**
 * `AVG(column)` — arithmetic mean as `Double`.
 *
 * ```kotlin
 * val mean = avg(Scores.value) `as` "mean"
 * ```
 */
fun <E> avg(column: Column<E, out Number>): Expr<Double> =
    Expr(Operand.FunctionCall("AVG", listOf(Operand.Col(column))), DoubleCodec)

// ─── Scalar string functions ───────────────────────────────────────────────────

/**
 * `UPPER(column)` — converts text to upper case.
 *
 * ```kotlin
 * where { upper(Users.code) eq "ACTIVE" }
 * ```
 */
fun <E> upper(column: Column<E, String>): Expr<String> =
    Expr(Operand.FunctionCall("UPPER", listOf(Operand.Col(column))), StringCodec)

fun upper(expr: Expr<String>): Expr<String> =
    Expr(Operand.FunctionCall("UPPER", listOf(expr.operand)), StringCodec)

/**
 * `LOWER(column)` — converts text to lower case.
 *
 * ```kotlin
 * where { lower(Users.email) eq "alice@example.com" }
 * ```
 */
fun <E> lower(column: Column<E, String>): Expr<String> =
    Expr(Operand.FunctionCall("LOWER", listOf(Operand.Col(column))), StringCodec)

fun lower(expr: Expr<String>): Expr<String> =
    Expr(Operand.FunctionCall("LOWER", listOf(expr.operand)), StringCodec)

/**
 * `LENGTH(column)` — character length of a text value, as `Int`.
 *
 * ```kotlin
 * where { length(Users.password) gte 8 }
 * ```
 */
fun <E> length(column: Column<E, String>): Expr<Int> =
    Expr(Operand.FunctionCall("LENGTH", listOf(Operand.Col(column))), IntCodec)

fun length(expr: Expr<String>): Expr<Int> =
    Expr(Operand.FunctionCall("LENGTH", listOf(expr.operand)), IntCodec)

/**
 * `TRIM(column)` — removes leading and trailing whitespace.
 *
 * ```kotlin
 * where { trim(Submissions.code) ne "" }
 * ```
 */
fun <E> trim(column: Column<E, String>): Expr<String> =
    Expr(Operand.FunctionCall("TRIM", listOf(Operand.Col(column))), StringCodec)

fun trim(expr: Expr<String>): Expr<String> =
    Expr(Operand.FunctionCall("TRIM", listOf(expr.operand)), StringCodec)

/**
 * `CONCAT(arg1, arg2, ...)` — SQL `CONCAT` function (null-safe concatenation).
 *
 * For SQL `||` operator concatenation use the `+` operator on string columns/exprs.
 *
 * ```kotlin
 * val fullName = concat(Users.firstName.asExpr(), Expr.literal(" ", StringCodec), Users.lastName.asExpr())
 * ```
 */
fun concat(first: Expr<String>, vararg rest: Expr<String>): Expr<String> =
    Expr(
        Operand.FunctionCall("CONCAT", listOf(first.operand) + rest.map { it.operand }),
        StringCodec,
    )

// ─── Scalar numeric functions ──────────────────────────────────────────────────

/**
 * `ABS(column)` — absolute value. Result type matches the column type.
 *
 * ```kotlin
 * where { abs(Accounts.balance) gt BigDecimal("100") }
 * ```
 */
fun <E, V : Number> abs(column: Column<E, V>): Expr<V> =
    Expr(Operand.FunctionCall("ABS", listOf(Operand.Col(column))), column.codec)

fun <V : Number> abs(expr: Expr<V>): Expr<V> =
    Expr(Operand.FunctionCall("ABS", listOf(expr.operand)), expr.codec)

/**
 * `ROUND(column)` — rounds to the nearest integer, keeping the column type.
 *
 * For `ROUND(value, scale)` see the overload below.
 */
fun <E, V : Number> round(column: Column<E, V>): Expr<V> =
    Expr(Operand.FunctionCall("ROUND", listOf(Operand.Col(column))), column.codec)

/**
 * `ROUND(column, scale)` — rounds to [scale] decimal places.
 *
 * ```kotlin
 * val price = round(Products.price, 2) `as` "price"
 * ```
 */
fun <E, V : Number> round(column: Column<E, V>, scale: Int): Expr<V> =
    Expr(
        Operand.FunctionCall("ROUND", listOf(Operand.Col(column), Operand.Literal(scale, IntCodec))),
        column.codec,
    )

fun <V : Number> round(expr: Expr<V>, scale: Int): Expr<V> =
    Expr(
        Operand.FunctionCall("ROUND", listOf(expr.operand, Operand.Literal(scale, IntCodec))),
        expr.codec,
    )

// ─── Null-handling functions ───────────────────────────────────────────────────

/**
 * `COALESCE(expr1, expr2, ...)` — returns the first non-null expression.
 *
 * All arguments must share the same value type [V]; the result codec is taken from [first].
 *
 * ```kotlin
 * // Use display name, fall back to username, then to "anonymous"
 * val name = coalesce(
 *     Users.displayName.asExpr(),
 *     Users.username.asExpr(),
 *     Expr.literal("anonymous", StringCodec),
 * ) `as` "name"
 * ```
 */
fun <V> coalesce(first: Expr<V>, vararg rest: Expr<V>): Expr<V> =
    Expr(
        Operand.FunctionCall("COALESCE", listOf(first.operand) + rest.map { it.operand }),
        first.codec,
    )

/**
 * `COALESCE(column, fallback)` — shorthand for a column + scalar default.
 *
 * ```kotlin
 * where { coalesce(Users.nickname, "unknown") eq "unknown" }
 * ```
 */
fun <E, V> coalesce(column: Column<E, V>, fallback: V): Expr<V> =
    Expr(
        Operand.FunctionCall(
            "COALESCE",
            listOf(Operand.Col(column), Operand.Literal(fallback, column.codec)),
        ),
        column.codec,
    )

// ─── Literal expression factory ────────────────────────────────────────────────

/**
 * Wraps a literal value in an [Expr] so it can participate in aggregate or expression APIs.
 *
 * ```kotlin
 * coalesce(Users.displayName.asExpr(), exprLiteral("anonymous", StringCodec))
 * ```
 */
fun <V> exprLiteral(value: V, codec: Codec<V>): Expr<V> =
    Expr(Operand.Literal(value, codec), codec)
