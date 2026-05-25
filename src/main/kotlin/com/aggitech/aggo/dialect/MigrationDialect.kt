package com.aggitech.aggo.dialect

import com.aggitech.aggo.schema.Codec

/**
 * Extends [SqlDialect] with DDL capabilities for migration generation.
 *
 * Separating DDL concerns from query-rendering concerns keeps [SqlDialect]
 * implementations lightweight — a dialect used only for DML (SELECT/INSERT/…)
 * does not need to know about column type mapping.
 *
 * To add support for a new database, implement both [quoteIdentifier],
 * [placeholder], and [columnSqlType]. Call [requireValidIdentifier] inside
 * [quoteIdentifier] — that is the single line of defence against identifier
 * injection in generated DDL.
 */
interface MigrationDialect : SqlDialect {

    /**
     * Returns the SQL column type keyword for the given codec's storage type.
     *
     * Implementations map [Codec.sqlType] (the concrete R2DBC driver type) to a
     * dialect-specific DDL keyword. [com.aggitech.aggo.schema.ValueClassCodec]
     * already delegates [Codec.sqlType] to its underlying raw codec, so custom
     * domain types resolve automatically without any special handling here.
     *
     * Throws [UnsupportedOperationException] for unmapped types — this is a
     * schema definition error and should surface at startup, not silently produce
     * invalid DDL.
     */
    fun columnSqlType(codec: Codec<*>): String
}
