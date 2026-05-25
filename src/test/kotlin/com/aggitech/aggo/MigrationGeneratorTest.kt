package com.aggitech.aggo

import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.migration.MigrationColumn
import com.aggitech.aggo.migration.MigrationSchema
import com.aggitech.aggo.migration.MigrationTable
import com.aggitech.aggo.migration.createTableSql
import com.aggitech.aggo.migration.dropTableSql
import com.aggitech.aggo.migration.migrationPlan
import com.aggitech.aggo.migration.migrationSchema
import com.aggitech.aggo.schema.BigDecimalCodec
import com.aggitech.aggo.schema.BooleanCodec
import com.aggitech.aggo.schema.Checks
import com.aggitech.aggo.schema.Codec
import com.aggitech.aggo.schema.InstantCodec
import com.aggitech.aggo.schema.IntCodec
import com.aggitech.aggo.schema.LongCodec
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
})
