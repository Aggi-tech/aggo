package com.aggitech.aggo.dsl

import com.aggitech.aggo.query.ArithmeticOp
import com.aggitech.aggo.query.DatePart
import com.aggitech.aggo.query.Expr
import com.aggitech.aggo.query.Operand
import com.aggitech.aggo.schema.Codec
import com.aggitech.aggo.schema.Column
import com.aggitech.aggo.schema.DoubleCodec
import com.aggitech.aggo.schema.InstantCodec
import com.aggitech.aggo.schema.LocalDateTimeCodec
import com.aggitech.aggo.schema.StringCodec
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

// ─── Generic function call — extensibility for hyperfunctions / dialect-specific SQL ──

/**
 * Builds an arbitrary `NAME(arg1, arg2, ...)` function-call expression.
 *
 * Use this as the escape hatch for functions Aggo doesn't wrap explicitly —
 * TimescaleDB hyperfunctions (`time_bucket_gapfill`, `first`, `last`,
 * `histogram`, `approx_percentile`, ...), Postgres extensions, or any other
 * `FUNC(args...)`-shaped call. You supply the result [codec] so the call
 * stays type-safe end to end.
 *
 * ```kotlin
 * val firstPrice = function("first", Trades.price.asExpr(), Trades.recordedAt.asExpr(), codec = BigDecimalCodec)
 * val bucket = function("time_bucket", interval("5 minutes"), Metrics.recordedAt.asExpr(), codec = LocalDateTimeCodec) `as` "bucket"
 * ```
 */
fun <V> function(name: String, vararg args: Expr<*>, codec: Codec<V>): Expr<V> =
    Expr(Operand.FunctionCall(name, args.map { it.operand }), codec)

/** `NAME(DISTINCT arg1, arg2, ...)` variant of [function]. */
fun <V> functionDistinct(name: String, vararg args: Expr<*>, codec: Codec<V>): Expr<V> =
    Expr(Operand.FunctionCall(name, args.map { it.operand }, distinct = true), codec)

// ─── EXTRACT(field FROM source) ───────────────────────────────────────────────

/**
 * `EXTRACT(field FROM column)` — pulls a date/time subfield (year, month,
 * hour, day-of-week, epoch, ...) out of a date/time column as a `Double`
 * (PostgreSQL returns `numeric`, decoded losslessly via [DoubleCodec]).
 *
 * ```kotlin
 * where { extract(DatePart.YEAR, Orders.placedAt) eq 2026.0 }
 * ```
 */
fun <E, V> extract(part: DatePart, column: Column<E, V>): Expr<Double> =
    Expr(Operand.Extract(part, Operand.Col(column)), DoubleCodec)

/** `EXTRACT(field FROM expr)` — overload for chaining onto another expression. */
fun <V> extract(part: DatePart, expr: Expr<V>): Expr<Double> =
    Expr(Operand.Extract(part, expr.operand), DoubleCodec)

/**
 * Shorthand helpers for the most common [DatePart]s — `year`, `month`, `day`,
 * `hour`, `minute`, `second`. Each is `extract(DatePart.X, column)`.
 *
 * ```kotlin
 * val y = year(Orders.placedAt) `as` "order_year"
 * aggregate(Orders) { project(y); project(countStar() `as` "total") }
 * ```
 */
fun <E, V> year(column: Column<E, V>): Expr<Double> = extract(DatePart.YEAR, column)
fun <E, V> month(column: Column<E, V>): Expr<Double> = extract(DatePart.MONTH, column)
fun <E, V> day(column: Column<E, V>): Expr<Double> = extract(DatePart.DAY, column)
fun <E, V> hour(column: Column<E, V>): Expr<Double> = extract(DatePart.HOUR, column)
fun <E, V> minute(column: Column<E, V>): Expr<Double> = extract(DatePart.MINUTE, column)
fun <E, V> second(column: Column<E, V>): Expr<Double> = extract(DatePart.SECOND, column)

// ─── date_trunc — rounds a timestamp down to a given precision ────────────────

/**
 * Rounds a date/time column down to the precision named by [part] (`YEAR`,
 * `MONTH`, `DAY`, `HOUR`, `MINUTE`, `SECOND` — support varies by dialect, see
 * [com.aggitech.aggo.dialect.SqlDialect.renderDateTrunc]). PostgreSQL renders
 * `date_trunc('day', col)`, MySQL a `DATE_FORMAT`-based equivalent, Oracle
 * `TRUNC(col, 'DD')` — always decoded as [LocalDateTime].
 *
 * ```kotlin
 * val day = dateTrunc(DatePart.DAY, Events.occurredAt) `as` "day"
 * aggregate(Events) { project(day); project(countStar() `as` "total") }
 * ```
 */
fun <E, V> dateTrunc(part: DatePart, column: Column<E, V>): Expr<LocalDateTime> =
    Expr(Operand.DateTrunc(part, Operand.Col(column)), LocalDateTimeCodec)

/** `date_trunc`-style rounding chained onto another expression. */
fun <V> dateTrunc(part: DatePart, expr: Expr<V>): Expr<LocalDateTime> =
    Expr(Operand.DateTrunc(part, expr.operand), LocalDateTimeCodec)

// ─── INTERVAL literals + date/time arithmetic ─────────────────────────────────

/**
 * A typed `quantity` × `unit` interval, e.g. `interval(30, IntervalUnit.DAY)`.
 *
 * There is no portable `INTERVAL` literal syntax across databases, so the
 * value is kept structured — never a free-form string — and each
 * [com.aggitech.aggo.dialect.SqlDialect] renders the form its database
 * understands (`$1 * INTERVAL '1 day'` in Postgres, `INTERVAL ? DAY` in
 * MySQL, `NUMTODSINTERVAL(?, 'DAY')` in Oracle, ...). The quantity is always
 * a bound parameter.
 *
 * Combine with date/time columns via [plusInterval] / [minusInterval], or
 * pass it as a bucket-width argument to functions like [timeBucket] /
 * [function].
 *
 * ```kotlin
 * val bucket = function("time_bucket", interval(1, IntervalUnit.HOUR), Metrics.recordedAt.asExpr(), codec = LocalDateTimeCodec)
 * ```
 */
fun interval(quantity: Int, unit: IntervalUnit): Expr<String> =
    Expr(Operand.IntervalLiteral(quantity, unit), StringCodec)

/**
 * `column + (quantity * INTERVAL unit)` — shifts a date/time column forward.
 *
 * PostgreSQL always promotes `date + interval` results to
 * `timestamp without time zone`; `timestamp`/`timestamptz + interval` keep
 * their original type. The return types below reflect that promotion.
 *
 * ```kotlin
 * where { Orders.placedAt.plusInterval(30, IntervalUnit.DAY) lt now() }
 * ```
 */
fun <E> Column<E, LocalDate>.plusInterval(quantity: Int, unit: IntervalUnit): Expr<LocalDateTime> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.ADD, Operand.IntervalLiteral(quantity, unit)), LocalDateTimeCodec)

fun <E> Column<E, LocalDate>.minusInterval(quantity: Int, unit: IntervalUnit): Expr<LocalDateTime> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.SUB, Operand.IntervalLiteral(quantity, unit)), LocalDateTimeCodec)

fun <E> Column<E, LocalDateTime>.plusInterval(quantity: Int, unit: IntervalUnit): Expr<LocalDateTime> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.ADD, Operand.IntervalLiteral(quantity, unit)), codec)

fun <E> Column<E, LocalDateTime>.minusInterval(quantity: Int, unit: IntervalUnit): Expr<LocalDateTime> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.SUB, Operand.IntervalLiteral(quantity, unit)), codec)

fun <E> Column<E, Instant>.plusInterval(quantity: Int, unit: IntervalUnit): Expr<Instant> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.ADD, Operand.IntervalLiteral(quantity, unit)), codec)

fun <E> Column<E, Instant>.minusInterval(quantity: Int, unit: IntervalUnit): Expr<Instant> =
    Expr(Operand.BinaryExpr(Operand.Col(this), ArithmeticOp.SUB, Operand.IntervalLiteral(quantity, unit)), codec)

// ─── now() ────────────────────────────────────────────────────────────────────

/**
 * `now()` — the current transaction timestamp (`timestamptz` in PostgreSQL),
 * decoded as [Instant].
 *
 * ```kotlin
 * where { Sessions.expiresAt lt now() }
 * ```
 */
fun now(): Expr<Instant> = Expr(Operand.FunctionCall("now", emptyList()), InstantCodec)

// ─── TimescaleDB time_bucket ──────────────────────────────────────────────────

/**
 * TimescaleDB `time_bucket(bucket_width, source)` — snaps timestamps onto a
 * fixed grid for hypertable downsampling and continuous aggregates. Requires
 * the `timescaledb` extension; [bucketWidth] is a structured [interval]
 * (e.g. `1 to IntervalUnit.HOUR`). The result keeps the source column's
 * type, matching `time_bucket`'s signature.
 *
 * ```kotlin
 * val bucket = timeBucket(1, IntervalUnit.HOUR, Metrics.recordedAt) `as` "bucket"
 * aggregate(Metrics) {
 *     project(bucket)
 *     project(avg(Metrics.value) `as` "avg_value")
 *     orderBy { bucket.asc() }
 * }
 * ```
 */
fun <E, V> timeBucket(quantity: Int, unit: IntervalUnit, column: Column<E, V>): Expr<V> =
    Expr(
        Operand.FunctionCall("time_bucket", listOf(Operand.IntervalLiteral(quantity, unit), Operand.Col(column))),
        column.codec,
    )
