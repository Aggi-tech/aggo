package com.aggitech.aggo

import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.migration.MigrationCheck
import com.aggitech.aggo.migration.MigrationColumn
import com.aggitech.aggo.migration.MigrationCustomType
import com.aggitech.aggo.migration.MigrationForeignKey
import com.aggitech.aggo.migration.MigrationIndex
import com.aggitech.aggo.migration.MigrationSchema
import com.aggitech.aggo.migration.MigrationTable
import com.aggitech.aggo.migration.MigrationUnique
import com.aggitech.aggo.migration.addIndexesSql
import com.aggitech.aggo.migration.createTableSql
import com.aggitech.aggo.migration.dropTableSql
import com.aggitech.aggo.migration.migrationPlan
import com.aggitech.aggo.migration.migrationSchema
import com.aggitech.aggo.schema.BigDecimalCodec
import com.aggitech.aggo.schema.BooleanCodec
import com.aggitech.aggo.schema.Checks
import com.aggitech.aggo.schema.Codec
import com.aggitech.aggo.schema.IndexMethod
import com.aggitech.aggo.schema.InstantCodec
import com.aggitech.aggo.schema.IntCodec
import com.aggitech.aggo.schema.LongCodec
import com.aggitech.aggo.schema.MigratableCodec
import com.aggitech.aggo.schema.StringCodec
import com.aggitech.aggo.schema.Table
import com.aggitech.aggo.schema.UuidCodec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.r2dbc.spi.Row

// M-1: fixture with diverse types, a nullable column, and a CHECK constraint
private object AccountsTable : Table<Unit>("accounts") {
    val id          = column("id",         UuidCodec,        isPrimaryKey = true)              { null }
    val accountName = column("name",       StringCodec,      check = Checks.notBlank())        { null }
    val balance     = column("balance",    BigDecimalCodec)                                    { null }
    val active      = column("active",     BooleanCodec)                                       { null }
    val createdAt   = column("created_at", InstantCodec)                                       { null }
    val note        = column("note",       StringCodec,      isNullable = true)                { null }
    override fun fromRow(row: Row): Unit = Unit
}

// M-2: composite primary key, multi-check column
private object OrderItemsTable : Table<Unit>("order_items") {
    val orderId   = column("order_id",   LongCodec, isPrimaryKey = true) { null }
    val productId = column("product_id", LongCodec, isPrimaryKey = true) { null }
    val qty       = column("qty",        IntCodec,  check = Checks.positive()) { null }
    override fun fromRow(row: Row): Unit = Unit
}

// M-3: table with no primary keys (valid DDL — no PRIMARY KEY clause should be emitted)
private object TagsTable : Table<Unit>("tags") {
    val tagName = column("name", StringCodec, check = Checks.length(max = 50)) { null }
    override fun fromRow(row: Row): Unit = Unit
}

// M-10 fixtures: MigratableCodec for a Postgres ENUM type
private enum class Priority { LOW, MEDIUM, HIGH }

private object PriorityCodec : MigratableCodec<Priority> {
    override val sqlType     = String::class.java
    override val ddlTypeName = "priority_level"
    override val createDdl   =
        "CREATE TYPE priority_level AS ENUM ('LOW', 'MEDIUM', 'HIGH');"

    override fun encode(value: Priority?): Any? = value?.name
    override fun decode(raw: Any?): Priority? = (raw as? String)?.let { Priority.valueOf(it) }
}

private object TasksTable : Table<Unit>("tasks") {
    val id       = column("id",       UuidCodec,     isPrimaryKey = true) { null }
    val priority = column("priority", PriorityCodec)                      { null }
    override fun fromRow(row: io.r2dbc.spi.Row): Unit = Unit
}

// M-18 fixture: second table that also references PriorityCodec — for deduplication test
private object SubtasksTable : Table<Unit>("subtasks") {
    val id       = column("id",       UuidCodec,     isPrimaryKey = true) { null }
    val priority = column("priority", PriorityCodec)                      { null }
    override fun fromRow(row: io.r2dbc.spi.Row): Unit = Unit
}

// M-11 fixture: MigratableCodec with createDdl = null (externally managed type)
private object ExternalTypeCodec : MigratableCodec<String> {
    override val sqlType     = String::class.java
    override val ddlTypeName = "external_enum"
    override val createDdl: String? = null  // type exists already, no CREATE needed

    override fun encode(value: String?): Any? = value
    override fun decode(raw: Any?): String? = raw?.toString()
}

private object ExternalTable : Table<Unit>("external_items") {
    val kind = column("kind", ExternalTypeCodec) { null }
    override fun fromRow(row: io.r2dbc.spi.Row): Unit = Unit
}

// M-19 fixtures: tables with indexes for index-tracking tests
private object SearchableTable : Table<Unit>("articles") {
    val id    = text("id").primaryKey().required { null }
    val title = text("title").index().required { null }
    val body  = text("body").index(method = IndexMethod.GIN).required { null }
    val score = integer("score").required { null }
    override fun fromRow(row: Row): Unit = Unit
}

// M-9 fixtures: must be top-level because named objects cannot be local in Kotlin
private object UnknownCodec : Codec<Any> {
    override val sqlType: Class<*> = java.io.Serializable::class.java
    override fun encode(value: Any?): Any? = value
    override fun decode(raw: Any?): Any? = raw
}
private object UnknownTable : Table<Unit>("unknown_table") {
    val col = column("col", UnknownCodec) { null }
    override fun fromRow(row: Row): Unit = Unit
}

class MigrationGeneratorTest : StringSpec({

    // M-1: baseline — verify column types, NOT NULL, nullable, check, primary key
    "createTableSql emits correct types, nullability, CHECK, and PRIMARY KEY" {
        AccountsTable.createTableSql(PostgresDialect) shouldBe
            "CREATE TABLE \"accounts\" (\n" +
            "    \"id\" UUID NOT NULL,\n" +
            "    \"name\" TEXT NOT NULL,\n" +
            "    \"balance\" NUMERIC NOT NULL,\n" +
            "    \"active\" BOOLEAN NOT NULL,\n" +
            "    \"created_at\" TIMESTAMPTZ NOT NULL,\n" +
            "    \"note\" TEXT,\n" +
            "    CONSTRAINT \"chk_accounts_name\" CHECK (trim(\"name\") <> ''),\n" +
            "    PRIMARY KEY (\"id\")\n" +
            ");"
    }

    // M-2: composite PK and multi-column constraint ordering
    "createTableSql with composite primary key lists all PK columns in order" {
        OrderItemsTable.createTableSql(PostgresDialect) shouldBe
            "CREATE TABLE \"order_items\" (\n" +
            "    \"order_id\" BIGINT NOT NULL,\n" +
            "    \"product_id\" BIGINT NOT NULL,\n" +
            "    \"qty\" INTEGER NOT NULL,\n" +
            "    CONSTRAINT \"chk_order_items_qty\" CHECK (\"qty\" > 0),\n" +
            "    PRIMARY KEY (\"order_id\", \"product_id\")\n" +
            ");"
    }

    // M-3: table with no PKs — PRIMARY KEY clause must be absent
    "createTableSql with no primary keys omits the PRIMARY KEY clause" {
        val sql = TagsTable.createTableSql(PostgresDialect)
        sql shouldBe
            "CREATE TABLE \"tags\" (\n" +
            "    \"name\" TEXT NOT NULL,\n" +
            "    CONSTRAINT \"chk_tags_name\" CHECK (char_length(\"name\") <= 50)\n" +
            ");"
    }

    // M-4: IF NOT EXISTS flag
    "createTableSql with ifNotExists=true prepends IF NOT EXISTS" {
        AccountsTable.createTableSql(PostgresDialect, ifNotExists = true) shouldStartWith
            "CREATE TABLE IF NOT EXISTS"
    }

    // M-5: generated columns still appear in DDL — they just need a manual DEFAULT
    "createTableSql includes generated columns (id, created_at in People fixture)" {
        val sql = People.createTableSql(PostgresDialect)
        // People.id: isGenerated=true, isPrimaryKey=true
        sql shouldContain "\"id\" INTEGER NOT NULL"
        // People.createdAt: isGenerated=true
        sql shouldContain "\"created_at\" TIMESTAMPTZ NOT NULL"
        sql shouldContain "PRIMARY KEY (\"id\")"
    }

    // M-6: ValueClassCodec resolves to the underlying primitive type (Email → TEXT)
    "createTableSql maps ValueClassCodec columns via the raw codec's sqlType" {
        People.createTableSql(PostgresDialect) shouldContain "\"email\" TEXT NOT NULL"
    }

    // M-7: drop without guard
    "dropTableSql returns bare DROP TABLE statement" {
        People.dropTableSql(PostgresDialect) shouldBe """DROP TABLE "people";"""
    }

    // M-8: drop with IF EXISTS guard
    "dropTableSql with ifExists=true emits DROP TABLE IF EXISTS" {
        People.dropTableSql(PostgresDialect, ifExists = true) shouldBe
            """DROP TABLE IF EXISTS "people";"""
    }

    // M-9: unknown driver type must throw UnsupportedOperationException so the
    // error surfaces at schema-init time, not silently producing empty/wrong DDL.
    "columnSqlType throws UnsupportedOperationException for unmapped R2DBC driver types" {
        shouldThrow<UnsupportedOperationException> {
            UnknownTable.createTableSql(PostgresDialect)
        }
    }

    "migrationSchema materializes an Aggo-independent snapshot" {
        val schema = migrationSchema("2026.05.25.001", listOf(AccountsTable), PostgresDialect)
        val table = schema.tables.single()

        schema.version shouldBe "2026.05.25.001"
        table.name shouldBe "accounts"
        table.columns.map { it.name to it.sqlType } shouldBe listOf(
            "id" to "UUID",
            "name" to "TEXT",
            "balance" to "NUMERIC",
            "active" to "BOOLEAN",
            "created_at" to "TIMESTAMPTZ",
            "note" to "TEXT",
        )
        table.primaryKey shouldBe listOf("id")
        table.checks.single().name shouldBe "chk_accounts_name"
    }

    "migrationPlan without previous schema creates all tables" {
        val schema = migrationSchema("2026.05.25.001", listOf(TagsTable), PostgresDialect)
        val plan = migrationPlan(schema, PostgresDialect, ifNotExists = true)

        plan.fromVersion shouldBe null
        plan.toVersion shouldBe "2026.05.25.001"
        plan.steps.single().change shouldBe "create table tags"
        plan.sql() shouldStartWith "CREATE TABLE IF NOT EXISTS"
    }

    "migrationPlan diff emits additive SQL and marks destructive changes manual" {
        val previous = migrationSchema("2026.05.25.001", listOf(TagsTable), PostgresDialect)
        val current = migrationSchema("2026.05.25.002", listOf(AccountsTable), PostgresDialect)
        val plan = migrationPlan(current, PostgresDialect, previous = previous)

        plan.steps.map { it.change } shouldBe listOf(
            "create table accounts",
            "drop table tags",
        )
        plan.steps.last().requiresManualMigration shouldBe true
    }

    "migrationPlan only auto-adds nullable columns on existing tables" {
        val previous = MigrationSchema(
            "v1",
            listOf(
                MigrationTable(
                    name = "users",
                    columns = listOf(MigrationColumn("id", "INTEGER", nullable = false)),
                    primaryKey = listOf("id"),
                )
            ),
        )
        val current = MigrationSchema(
            "v2",
            listOf(
                MigrationTable(
                    name = "users",
                    columns = listOf(
                        MigrationColumn("id", "INTEGER", nullable = false),
                        MigrationColumn("nickname", "TEXT", nullable = true),
                        MigrationColumn("age", "INTEGER", nullable = false),
                    ),
                    primaryKey = listOf("id"),
                )
            ),
        )

        val plan = migrationPlan(current, PostgresDialect, previous = previous)

        plan.steps[0].change shouldBe "add column users.nickname"
        plan.steps[0].sql shouldBe """ALTER TABLE "users" ADD COLUMN "nickname" TEXT;"""
        plan.steps[1].change shouldBe "add non-null column users.age"
        plan.steps[1].requiresManualMigration shouldBe true
    }

    "migrationPlan diff with ifNotExists=true uses CREATE TABLE IF NOT EXISTS for new tables" {
        val previous = migrationSchema("v1", listOf(TagsTable), PostgresDialect)
        val current = migrationSchema("v2", listOf(TagsTable, AccountsTable), PostgresDialect)
        val plan = migrationPlan(current, PostgresDialect, previous = previous, ifNotExists = true)

        val createStep = plan.steps.first { it.change == "create table accounts" }
        createStep.sql!! shouldStartWith "CREATE TABLE IF NOT EXISTS"
    }

    // M-10: MigratableCodec uses ddlTypeName as the column DDL type
    "columnSqlType returns ddlTypeName for MigratableCodec" {
        val sql = TasksTable.createTableSql(PostgresDialect)
        sql shouldContain "\"priority\" priority_level NOT NULL"
    }

    // M-11: migrationSchema collects MigratableCodec.createDdl into customTypes
    "migrationSchema collects MigratableCodec custom types with createDdl" {
        val schema = migrationSchema("v1", listOf(TasksTable), PostgresDialect)
        schema.customTypes.size shouldBe 1
        schema.customTypes.single().name shouldBe "priority_level"
        schema.customTypes.single().createDdl shouldBe
            "CREATE TYPE priority_level AS ENUM ('LOW', 'MEDIUM', 'HIGH');"
    }

    // M-12: MigratableCodec with null createDdl is NOT collected in customTypes
    "migrationSchema skips MigratableCodec with null createDdl" {
        val schema = migrationSchema("v1", listOf(ExternalTable), PostgresDialect)
        schema.customTypes shouldBe emptyList()
    }

    // M-13: migrationPlan without previous schema emits custom type DDL before CREATE TABLE
    "migrationPlan emits custom type step before CREATE TABLE on first run" {
        val schema = migrationSchema("v1", listOf(TasksTable), PostgresDialect)
        val plan = migrationPlan(schema, PostgresDialect)

        plan.steps.size shouldBe 2
        plan.steps[0].change shouldBe "create custom type priority_level"
        plan.steps[0].sql shouldBe "CREATE TYPE priority_level AS ENUM ('LOW', 'MEDIUM', 'HIGH');"
        plan.steps[0].requiresManualMigration shouldBe false
        plan.steps[1].change shouldBe "create table tasks"
    }

    // M-14: migrationPlan diff — new custom type emits DDL step
    "migrationPlan diff adds new custom type step when type appears for the first time" {
        val previous = migrationSchema("v1", listOf(TagsTable), PostgresDialect)
        val current  = migrationSchema("v2", listOf(TagsTable, TasksTable), PostgresDialect)
        val plan = migrationPlan(current, PostgresDialect, previous = previous)

        val typeStep = plan.steps.first { it.change == "create custom type priority_level" }
        typeStep.sql shouldBe "CREATE TYPE priority_level AS ENUM ('LOW', 'MEDIUM', 'HIGH');"
        typeStep.requiresManualMigration shouldBe false
    }

    // M-15: migrationPlan diff — unchanged custom type emits no step
    "migrationPlan diff emits no step when custom type DDL is unchanged" {
        val previous = migrationSchema("v1", listOf(TasksTable), PostgresDialect)
        val current  = migrationSchema("v2", listOf(TasksTable), PostgresDialect)
        val plan = migrationPlan(current, PostgresDialect, previous = previous)

        plan.steps.none { it.change.contains("priority_level") } shouldBe true
    }

    // M-16: migrationPlan diff — changed createDdl becomes a manual step
    "migrationPlan diff marks changed custom type DDL as manual migration" {
        val previous = MigrationSchema(
            "v1",
            listOf(MigrationTable("tasks", listOf(MigrationColumn("id", "UUID", false)))),
            customTypes = listOf(MigrationCustomType("priority_level", "CREATE TYPE priority_level AS ENUM ('LOW');"))
        )
        val current = migrationSchema("v2", listOf(TasksTable), PostgresDialect)
        val plan = migrationPlan(current, PostgresDialect, previous = previous)

        val manualStep = plan.steps.first { it.change.contains("priority_level") }
        manualStep.requiresManualMigration shouldBe true
        manualStep.change shouldContain "DDL changed"
    }

    // M-17: migrationPlan diff — dropped custom type is flagged as manual
    "migrationPlan diff marks removed custom type as manual migration" {
        val previous = MigrationSchema(
            "v1",
            listOf(MigrationTable("tasks", listOf(MigrationColumn("id", "UUID", false)))),
            customTypes = listOf(MigrationCustomType("old_type", "CREATE TYPE old_type AS ENUM ('X');"))
        )
        val current = migrationSchema("v2", listOf(TagsTable), PostgresDialect)
        val plan = migrationPlan(current, PostgresDialect, previous = previous)

        val dropStep = plan.steps.first { it.change.contains("old_type") }
        dropStep.requiresManualMigration shouldBe true
        dropStep.change shouldContain "removed"
    }

    // M-18: same MigratableCodec referenced by two different tables contributes only one customType entry
    "migrationSchema deduplicates same MigratableCodec across multiple tables" {
        val schema = migrationSchema("v1", listOf(TasksTable, SubtasksTable), PostgresDialect)
        schema.customTypes.size shouldBe 1
        schema.customTypes.single().name shouldBe "priority_level"
    }

    // M-19: migrationSchema captures index declarations from ColumnBuilder.index()
    "migrationSchema materializes index declarations from ColumnBuilder" {
        val schema = migrationSchema("v1", listOf(SearchableTable), PostgresDialect)
        val table = schema.tables.single()

        table.indexes.size shouldBe 2
        table.indexes[0].name shouldBe "idx_articles_title"
        table.indexes[0].columns shouldBe listOf("title")
        table.indexes[0].method shouldBe "btree"
        table.indexes[1].name shouldBe "idx_articles_body"
        table.indexes[1].columns shouldBe listOf("body")
        table.indexes[1].method shouldBe "gin"
    }

    // M-20: migrationPlan without previous schema emits CREATE INDEX after CREATE TABLE + FK steps
    "migrationPlan emits CREATE INDEX steps after CREATE TABLE on first run" {
        val schema = migrationSchema("v1", listOf(SearchableTable), PostgresDialect)
        val plan = migrationPlan(schema, PostgresDialect)

        val tableStep = plan.steps.first { it.change.startsWith("create table") }
        val indexSteps = plan.steps.filter { it.change.startsWith("create index") }

        (plan.steps.indexOf(tableStep) < plan.steps.indexOf(indexSteps.first())) shouldBe true
        indexSteps.size shouldBe 2
        indexSteps[0].change shouldBe "create index articles.idx_articles_title"
        indexSteps[0].sql shouldBe """CREATE INDEX "idx_articles_title" ON "articles" USING btree ("title");"""
        indexSteps[1].change shouldBe "create index articles.idx_articles_body"
        indexSteps[1].sql shouldBe """CREATE INDEX "idx_articles_body" ON "articles" USING gin ("body");"""
    }

    // M-21: addIndexesSql returns CREATE INDEX statements for a table
    "addIndexesSql emits correct CREATE INDEX DDL" {
        val sqls = SearchableTable.addIndexesSql(PostgresDialect)
        sqls.size shouldBe 2
        sqls[0] shouldBe """CREATE INDEX "idx_articles_title" ON "articles" USING btree ("title");"""
        sqls[1] shouldBe """CREATE INDEX "idx_articles_body" ON "articles" USING gin ("body");"""
    }

    // M-22: diff detects a new index on an existing table and auto-generates CREATE INDEX
    "migrationPlan diff auto-generates CREATE INDEX for a new index" {
        val previous = MigrationSchema(
            "v1",
            listOf(MigrationTable("articles", listOf(
                MigrationColumn("id", "TEXT", nullable = false),
                MigrationColumn("title", "TEXT", nullable = false),
            ))),
        )
        val current = MigrationSchema(
            "v2",
            listOf(MigrationTable("articles",
                columns = listOf(
                    MigrationColumn("id", "TEXT", nullable = false),
                    MigrationColumn("title", "TEXT", nullable = false),
                ),
                indexes = listOf(MigrationIndex("idx_articles_title", listOf("title"), "btree")),
            )),
        )
        val plan = migrationPlan(current, PostgresDialect, previous = previous)

        plan.steps.size shouldBe 1
        plan.steps[0].change shouldBe "create index articles.idx_articles_title"
        plan.steps[0].sql shouldBe """CREATE INDEX "idx_articles_title" ON "articles" USING btree ("title");"""
        plan.steps[0].requiresManualMigration shouldBe false
    }

    // M-23: diff detects a dropped index and auto-generates DROP INDEX (safe — no data loss)
    "migrationPlan diff auto-generates DROP INDEX for a removed index" {
        val previous = MigrationSchema(
            "v1",
            listOf(MigrationTable("articles",
                columns = listOf(MigrationColumn("id", "TEXT", nullable = false)),
                indexes = listOf(MigrationIndex("idx_articles_title", listOf("title"), "btree")),
            )),
        )
        val current = MigrationSchema(
            "v2",
            listOf(MigrationTable("articles",
                columns = listOf(MigrationColumn("id", "TEXT", nullable = false)),
            )),
        )
        val plan = migrationPlan(current, PostgresDialect, previous = previous)

        plan.steps.size shouldBe 1
        plan.steps[0].change shouldBe "drop index idx_articles_title"
        plan.steps[0].sql shouldBe """DROP INDEX "idx_articles_title";"""
        plan.steps[0].requiresManualMigration shouldBe false
    }

    // M-24: diff detects a changed index (method or columns) and flags manual migration
    "migrationPlan diff marks a changed index as requiresManualMigration" {
        val previous = MigrationSchema(
            "v1",
            listOf(MigrationTable("articles",
                columns = listOf(MigrationColumn("id", "TEXT", nullable = false)),
                indexes = listOf(MigrationIndex("idx_articles_title", listOf("title"), "btree")),
            )),
        )
        val current = MigrationSchema(
            "v2",
            listOf(MigrationTable("articles",
                columns = listOf(MigrationColumn("id", "TEXT", nullable = false)),
                indexes = listOf(MigrationIndex("idx_articles_title", listOf("title"), "hash")),
            )),
        )
        val plan = migrationPlan(current, PostgresDialect, previous = previous)

        plan.steps.size shouldBe 1
        plan.steps[0].change shouldBe "change index articles.idx_articles_title"
        plan.steps[0].requiresManualMigration shouldBe true
    }

    "migrationPlan diff records audit metadata and reverse SQL for safe column and check changes" {
        val previous = MigrationSchema(
            "v1",
            listOf(MigrationTable(
                name = "users",
                columns = listOf(MigrationColumn("id", "INTEGER", nullable = false)),
                primaryKey = listOf("id"),
            )),
        )
        val current = MigrationSchema(
            "v2",
            listOf(MigrationTable(
                name = "users",
                columns = listOf(
                    MigrationColumn("id", "INTEGER", nullable = false),
                    MigrationColumn("nickname", "TEXT", nullable = true),
                ),
                checks = listOf(MigrationCheck("chk_users_nickname", "char_length(\"nickname\") <= 64")),
                primaryKey = listOf("id"),
            )),
        )

        val plan = migrationPlan(current, PostgresDialect, previous = previous)

        val addColumn = plan.steps.first { it.change == "add column users.nickname" }
        addColumn.audit!!.operation shouldBe "add"
        addColumn.audit.targetType shouldBe "column"
        addColumn.audit.targetName shouldBe "users.nickname"
        addColumn.audit.reversible shouldBe true
        addColumn.audit.reverseSql shouldBe """ALTER TABLE "users" DROP COLUMN "nickname";"""

        val addCheck = plan.steps.first { it.change == "add check users.chk_users_nickname" }
        addCheck.audit!!.operation shouldBe "add"
        addCheck.audit.targetType shouldBe "check"
        addCheck.audit.targetName shouldBe "users.chk_users_nickname"
        addCheck.audit.reversible shouldBe true
        addCheck.audit.reverseSql shouldBe """ALTER TABLE "users" DROP CONSTRAINT "chk_users_nickname";"""
    }

    // M-25: a new table added in a diff must carry its FK and index steps — the bug was that
    // diffSchemas emitted CREATE TABLE but silently dropped FK/index steps for new tables,
    // causing the snapshot to record FKs that were never applied to the database.
    "migrationPlan diff emits FK and index steps for a newly added table, ordered after CREATE TABLE" {
        val previous = MigrationSchema(
            "v1",
            listOf(MigrationTable("environments", listOf(MigrationColumn("id", "TEXT", nullable = false)), primaryKey = listOf("id"))),
        )
        val current = MigrationSchema(
            "v2",
            listOf(
                MigrationTable("environments", listOf(MigrationColumn("id", "TEXT", nullable = false)), primaryKey = listOf("id")),
                MigrationTable(
                    name = "deployments",
                    columns = listOf(
                        MigrationColumn("id", "TEXT", nullable = false),
                        MigrationColumn("environment_id", "TEXT", nullable = false),
                    ),
                    primaryKey = listOf("id"),
                    foreignKeys = listOf(
                        MigrationForeignKey(
                            name = "fk_deployments_environment_id",
                            column = "environment_id",
                            referencedTable = "environments",
                            referencedColumn = "id",
                            onDelete = "CASCADE",
                            onUpdate = "RESTRICT",
                        )
                    ),
                    indexes = listOf(MigrationIndex("idx_deployments_environment_id", listOf("environment_id"), "btree")),
                ),
            ),
        )

        val plan = migrationPlan(current, PostgresDialect, previous = previous)

        val changes = plan.steps.map { it.change }
        changes.contains("create table deployments") shouldBe true
        changes.contains("add foreign key deployments.fk_deployments_environment_id") shouldBe true
        changes.contains("create index deployments.idx_deployments_environment_id") shouldBe true

        val tableIdx = changes.indexOf("create table deployments")
        val fkIdx    = changes.indexOf("add foreign key deployments.fk_deployments_environment_id")
        val idxIdx   = changes.indexOf("create index deployments.idx_deployments_environment_id")
        (tableIdx < fkIdx) shouldBe true
        (tableIdx < idxIdx) shouldBe true

        plan.steps.first { it.change == "add foreign key deployments.fk_deployments_environment_id" }
            .sql shouldBe """ALTER TABLE "deployments" ADD CONSTRAINT "fk_deployments_environment_id" FOREIGN KEY ("environment_id") REFERENCES "environments" ("id") ON DELETE CASCADE ON UPDATE RESTRICT;"""

        plan.steps.first { it.change == "create index deployments.idx_deployments_environment_id" }
            .sql shouldBe """CREATE INDEX "idx_deployments_environment_id" ON "deployments" USING btree ("environment_id");"""
    }

    "migrationPlan diff emits reversible SQL for dropped check, unique, and foreign key constraints" {
        val previous = MigrationSchema(
            "v1",
            listOf(
                MigrationTable("accounts", listOf(MigrationColumn("id", "INTEGER", nullable = false)), primaryKey = listOf("id")),
                MigrationTable(
                    name = "users",
                    columns = listOf(
                        MigrationColumn("id", "INTEGER", nullable = false),
                        MigrationColumn("account_id", "INTEGER", nullable = false),
                        MigrationColumn("email", "TEXT", nullable = false),
                    ),
                    checks = listOf(MigrationCheck("chk_users_email", "trim(\"email\") <> ''")),
                    uniques = listOf(MigrationUnique("uq_users_email", listOf("email"))),
                    primaryKey = listOf("id"),
                    foreignKeys = listOf(
                        MigrationForeignKey(
                            name = "fk_users_account_id",
                            column = "account_id",
                            referencedTable = "accounts",
                            referencedColumn = "id",
                            onDelete = "CASCADE",
                            onUpdate = "RESTRICT",
                        )
                    ),
                ),
            ),
        )
        val current = MigrationSchema(
            "v2",
            listOf(
                MigrationTable("accounts", listOf(MigrationColumn("id", "INTEGER", nullable = false)), primaryKey = listOf("id")),
                MigrationTable(
                    name = "users",
                    columns = listOf(
                        MigrationColumn("id", "INTEGER", nullable = false),
                        MigrationColumn("account_id", "INTEGER", nullable = false),
                        MigrationColumn("email", "TEXT", nullable = false),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )

        val plan = migrationPlan(current, PostgresDialect, previous = previous)

        val dropCheck = plan.steps.first { it.change == "drop check users.chk_users_email" }
        dropCheck.sql shouldBe """ALTER TABLE "users" DROP CONSTRAINT "chk_users_email";"""
        dropCheck.audit!!.reversible shouldBe true
        dropCheck.audit.reverseSql shouldBe """ALTER TABLE "users" ADD CONSTRAINT "chk_users_email" CHECK (trim("email") <> '');"""

        val dropUnique = plan.steps.first { it.change == "drop unique users.uq_users_email" }
        dropUnique.sql shouldBe """ALTER TABLE "users" DROP CONSTRAINT "uq_users_email";"""
        dropUnique.audit!!.reversible shouldBe true
        dropUnique.audit.reverseSql shouldBe """ALTER TABLE "users" ADD CONSTRAINT "uq_users_email" UNIQUE ("email");"""

        val dropFk = plan.steps.first { it.change == "drop foreign key users.fk_users_account_id" }
        dropFk.sql shouldBe """ALTER TABLE "users" DROP CONSTRAINT "fk_users_account_id";"""
        dropFk.audit!!.reversible shouldBe true
        dropFk.audit.reverseSql shouldBe """ALTER TABLE "users" ADD CONSTRAINT "fk_users_account_id" FOREIGN KEY ("account_id") REFERENCES "accounts" ("id") ON DELETE CASCADE ON UPDATE RESTRICT;"""
    }
})
