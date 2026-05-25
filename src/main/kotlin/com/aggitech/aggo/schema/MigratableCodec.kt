package com.aggitech.aggo.schema

/**
 * Optional extension for [Codec] implementations that want to control their own
 * PostgreSQL DDL type in generated migrations.
 *
 * Implement this when the standard [com.aggitech.aggo.dialect.PostgresDialect]
 * type mapping (TEXT, INTEGER, …) is not specific enough — for example when the
 * column should use a PostgreSQL ENUM or DOMAIN type.
 *
 * [createDdl] is emitted once per migration plan, **before** any `CREATE TABLE`
 * statement that references the type. Use `CREATE TYPE … IF NOT EXISTS` so the
 * statement is idempotent across re-runs.
 *
 * Example — a Postgres ENUM backed by a Kotlin enum class:
 *
 * ```kotlin
 * enum class Status { ACTIVE, INACTIVE, SUSPENDED }
 *
 * object StatusCodec : MigratableCodec<Status> {
 *     override val sqlType     = String::class.java
 *     override val ddlTypeName = "status_type"
 *     override val createDdl   =
 *         "CREATE TYPE status_type AS ENUM ('ACTIVE', 'INACTIVE', 'SUSPENDED');"
 *
 *     override fun encode(value: Status?): Any? = value?.name
 *     override fun decode(raw: Any?): Status? =
 *         (raw as? String)?.let { Status.valueOf(it) }
 * }
 * ```
 *
 * Example — a Postgres DOMAIN:
 *
 * ```kotlin
 * object EmailCodec : MigratableCodec<Email> {
 *     override val sqlType     = String::class.java
 *     override val ddlTypeName = "email_domain"
 *     override val createDdl   = """
 *         CREATE DOMAIN email_domain AS TEXT
 *             CHECK (VALUE ~* '^[^@\s]+@[^@\s]+\.[^@\s]+${'$'}' AND char_length(VALUE) <= 255);
 *     """.trimIndent()
 *
 *     override fun encode(value: Email?): Any? = value?.toString()
 *     override fun decode(raw: Any?): Email? = (raw as? String)?.let { Email.of(it) }
 * }
 * ```
 *
 * If the type is pre-existing or managed externally, return `null` from [createDdl]
 * and only set [ddlTypeName] so the column DDL references the correct type name.
 */
interface MigratableCodec<V> : Codec<V> {

    /**
     * The SQL type name to use in column DDL, e.g. `"status_type"` or `"email_domain"`.
     * Must be a valid SQL identifier — no quoting is applied.
     */
    val ddlTypeName: String

    /**
     * Full DDL statement that creates the type. Emitted as the first step of the
     * migration plan before any `CREATE TABLE` that references it.
     *
     * Return `null` when the type is pre-existing or managed externally.
     */
    val createDdl: String? get() = null
}
