package com.aggitech.aggo.query

/**
 * The common subset of date/time units that every supported [com.aggitech.aggo.dialect.SqlDialect]
 * (Postgres, MySQL, Oracle, ...) can express as a typed interval — `quantity` worth of `unit`.
 *
 * Deliberately closed and small: PostgreSQL accepts free-form `INTERVAL '1 day'` strings,
 * but MySQL (`INTERVAL n unit`) and Oracle (`NUMTODSINTERVAL`/`NUMTOYMINTERVAL`) require a
 * structured `(quantity, unit)` pair with a known keyword. Keeping [IntervalUnit] closed lets
 * each dialect render the correct, injection-free SQL for [com.aggitech.aggo.dsl.interval]
 * without parsing a free-form string.
 */
enum class IntervalUnit {
    YEAR, MONTH, DAY, HOUR, MINUTE, SECOND,
}
