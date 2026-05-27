package com.aggitech.aggo.dialect

/**
 * [SqlDialect] decorator that prefixes every table reference with a schema name.
 *
 * All other dialect behavior is delegated to [base] unchanged. No connection
 * state is changed, so pooled connections remain clean between tenants.
 *
 * Use this class when you need to render tenant-qualified DML SQL outside the
 * runtime decorators, for example in renderer tests, custom query inspection,
 * or low-level tooling that calls `renderSelect`, `renderInsert`, or similar
 * functions directly.
 *
 * ```kotlin
 * import com.aggitech.aggo.dialect.PostgresDialect
 * import com.aggitech.aggo.dialect.forSchema
 * import com.aggitech.aggo.dsl.select
 * import com.aggitech.aggo.render.renderSelect
 *
 * val dialect = PostgresDialect.forSchema("acme")
 * val rendered = renderSelect(select(UsersTable), dialect)
 *
 * check(rendered.sql.contains("""FROM "acme"."users""""))
 * ```
 *
 * @param base dialect that provides placeholder, quoting, pagination, and
 * insert-returning behavior.
 * @param schema PostgreSQL schema name used to qualify every table reference.
 * It is validated with [requireValidIdentifier].
 */
class MultiSchemaSqlDialect(
    private val base: SqlDialect,
    val schema: String,
) : SqlDialect by base {

    init {
        requireValidIdentifier(schema)
    }

    /**
     * Renders a table reference as `"schema"."table"` using [base]'s identifier
     * quoting rules for both parts.
     *
     * This is the only method overridden by the decorator. All renderers call
     * this hook for table references, so `FROM`, `JOIN`, `INSERT INTO`,
     * `UPDATE`, `DELETE FROM`, predicates, and `ORDER BY` references stay
     * consistently qualified.
     *
     * ```kotlin
     * val dialect = PostgresDialect.forSchema("acme")
     *
     * check(dialect.qualifyTableName("users") == """"acme"."users"""")
     * ```
     *
     * @param tableName unqualified Aggo table name from `Table.name`.
     * @return quoted schema-qualified SQL table reference.
     */
    override fun qualifyTableName(tableName: String): String =
        "${base.quoteIdentifier(schema)}.${base.quoteIdentifier(tableName)}"
}

/**
 * Schema-qualified decorator for dialects that also generate DDL.
 *
 * Use this overload when a migration plan must create tenant-local tables:
 *
 * ```kotlin
 * import com.aggitech.aggo.dialect.PostgresDialect
 * import com.aggitech.aggo.dialect.forSchema
 * import com.aggitech.aggo.migration.migrationPlan
 * import com.aggitech.aggo.migration.migrationSchema
 *
 * val dialect = PostgresDialect.forSchema("acme")
 * val schema = migrationSchema("2026.06.01.001", listOf(UsersTable), dialect)
 * val plan = migrationPlan(schema, dialect)
 *
 * check(plan.sql().contains("""CREATE TABLE "acme"."users""""))
 * ```
 *
 * @param base migration dialect that provides DML rendering and DDL type
 * mapping.
 * @param schema PostgreSQL schema name used to qualify every table reference.
 */
class MultiSchemaMigrationDialect(
    private val base: MigrationDialect,
    val schema: String,
) : MigrationDialect by base {

    init {
        requireValidIdentifier(schema)
    }

    /**
     * Renders a table reference as `"schema"."table"` for DML and DDL clauses.
     *
     * Migration generators call the same hook used by query renderers, so a
     * tenant migration plan can create tables, foreign keys, indexes, and the
     * `aggo_schema_versions` lookup in the tenant schema.
     *
     * ```kotlin
     * val dialect = PostgresDialect.forSchema("globex")
     *
     * check(dialect.qualifyTableName("orders") == """"globex"."orders"""")
     * ```
     */
    override fun qualifyTableName(tableName: String): String =
        "${base.quoteIdentifier(schema)}.${base.quoteIdentifier(tableName)}"
}

/**
 * Builds a schema-qualified DML dialect decorator.
 *
 * Use it when the caller has only a [SqlDialect] and needs query rendering,
 * not migration DDL generation.
 *
 * ```kotlin
 * val tenantDialect = PostgresDialect.forSchema("globex")
 * val sql = renderDelete(delete(UsersTable), tenantDialect).sql
 *
 * check(sql == """DELETE FROM "globex"."users"""")
 * ```
 *
 * @param schema schema name to prepend to every table reference.
 */
fun SqlDialect.forSchema(schema: String): MultiSchemaSqlDialect =
    MultiSchemaSqlDialect(this, schema)

/**
 * Builds a schema-qualified DML + DDL dialect decorator.
 *
 * Prefer this overload when generating migrations for a specific tenant schema.
 *
 * ```kotlin
 * val tenantDialect = PostgresDialect.forSchema("acme")
 * val plan = migrationPlan(
 *     migrationSchema("2026.06.01.001", listOf(UsersTable), tenantDialect),
 *     tenantDialect,
 * )
 * ```
 *
 * @param schema schema name to prepend to every table reference.
 */
fun MigrationDialect.forSchema(schema: String): MultiSchemaMigrationDialect =
    MultiSchemaMigrationDialect(this, schema)
