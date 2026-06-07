package com.aggitech.aggo.query

/**
 * Date/time subfields recognised by PostgreSQL's `EXTRACT(field FROM source)`
 * and `date_trunc('field', source)`.
 *
 * Closed enum — every member maps to a fixed, trusted SQL keyword, so
 * [Operand.Extract] can render `field` directly without binding or quoting
 * (and without risking identifier injection).
 *
 * [sql] is the bare keyword for `EXTRACT`; [truncName] is the lower-cased
 * string literal `date_trunc` expects (bound as an ordinary parameter).
 */
enum class DatePart(val sql: String) {
    MILLENNIUM("MILLENNIUM"),
    CENTURY("CENTURY"),
    DECADE("DECADE"),
    YEAR("YEAR"),
    QUARTER("QUARTER"),
    MONTH("MONTH"),
    WEEK("WEEK"),
    DAY("DAY"),
    HOUR("HOUR"),
    MINUTE("MINUTE"),
    SECOND("SECOND"),
    MILLISECONDS("MILLISECONDS"),
    MICROSECONDS("MICROSECONDS"),
    DOW("DOW"),
    DOY("DOY"),
    ISOYEAR("ISOYEAR"),
    EPOCH("EPOCH"),
    TIMEZONE("TIMEZONE"),
    ;

    /** Lower-cased field name as `date_trunc('…', source)` expects it as a string literal. */
    val truncName: String get() = sql.lowercase()
}
