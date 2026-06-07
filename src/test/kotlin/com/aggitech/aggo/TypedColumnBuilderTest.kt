package com.aggitech.aggo

import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.migration.createTableSql
import com.aggitech.aggo.schema.SQL_TYPE_REGEX
import com.aggitech.aggo.schema.StringCodec
import com.aggitech.aggo.schema.Table
import com.aggitech.aggo.schema.ValueClassCodec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.r2dbc.spi.Row
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@JvmInline value class Username(val raw: String)
private val UsernameCodec = ValueClassCodec(StringCodec, ::Username, Username::raw)

private object SizedTypesTable : Table<Unit>("sized_types") {
    val pid       = uuid("id", isPrimaryKey = true) { null }
    val title     = varchar("name", length = 100) { null }
    val handle    = varchar("handle", length = 30, codec = UsernameCodec) { null }
    val bio       = text("bio", isNullable = true) { null }
    val score     = smallint("score") { null }
    val followers = integer("followers") { null }
    val balance   = decimal("balance", precision = 12, scale = 2) { null }
    val ratioF    = real("ratio_f") { null }
    val ratioD    = doublePrecision("ratio_d") { null }
    val createdAt = timestamptz("created_at") { null }
    val updatedAt = timestamp("updated_at") { null }
    val birthday  = date("birthday", isNullable = true) { null }
    val active    = boolean("active") { null }
    val avatar    = bytea("avatar", isNullable = true) { null }
    val pageviews = bigint("pageviews") { null }
    override fun fromRow(row: Row): Unit = Unit
}

class TypedColumnBuilderTest : StringSpec({

    "typed column builders emit sized SQL types in DDL" {
        val sql = SizedTypesTable.createTableSql(PostgresDialect)
        sql shouldContain "\"id\" UUID NOT NULL"
        sql shouldContain "\"name\" VARCHAR(100) NOT NULL"
        sql shouldContain "\"handle\" VARCHAR(30) NOT NULL"
        sql shouldContain "\"bio\" TEXT,"
        sql shouldContain "\"score\" SMALLINT NOT NULL"
        sql shouldContain "\"followers\" INTEGER NOT NULL"
        sql shouldContain "\"balance\" NUMERIC(12, 2) NOT NULL"
        sql shouldContain "\"ratio_f\" REAL NOT NULL"
        sql shouldContain "\"ratio_d\" DOUBLE PRECISION NOT NULL"
        sql shouldContain "\"created_at\" TIMESTAMPTZ NOT NULL"
        sql shouldContain "\"updated_at\" TIMESTAMP NOT NULL"
        sql shouldContain "\"birthday\" DATE,"
        sql shouldContain "\"active\" BOOLEAN NOT NULL"
        sql shouldContain "\"avatar\" BYTEA,"
        sql shouldContain "\"pageviews\" BIGINT NOT NULL"
    }

    "varchar with non-positive length is rejected at construction time" {
        shouldThrow<IllegalArgumentException> {
            object : Table<Unit>("bad") {
                @Suppress("unused")
                val v = varchar("v", length = 0) { null }
                override fun fromRow(row: Row): Unit = Unit
            }
        }
    }

    "decimal with scale > precision is rejected" {
        shouldThrow<IllegalArgumentException> {
            object : Table<Unit>("bad") {
                @Suppress("unused")
                val d = decimal("d", precision = 5, scale = 10) { null }
                override fun fromRow(row: Row): Unit = Unit
            }
        }
    }

    "decimal with negative precision is rejected" {
        shouldThrow<IllegalArgumentException> {
            object : Table<Unit>("bad") {
                @Suppress("unused")
                val d = decimal("d", precision = -1, scale = 0) { null }
                override fun fromRow(row: Row): Unit = Unit
            }
        }
    }

    "tsid and ulid builders attach default check constraints and CHAR-typed DDL" {
        val sql = ProfilesTable.createTableSql(PostgresDialect)
        sql shouldContain "\"id\" VARCHAR(13) NOT NULL"
        sql shouldContain "\"alt_id\" VARCHAR(26) NOT NULL"
        sql shouldContain "CONSTRAINT \"chk_profiles_id\" CHECK (char_length(\"id\") = 13"
        sql shouldContain "CONSTRAINT \"chk_profiles_alt_id\" CHECK (char_length(\"alt_id\") = 26"
    }

    "raw sqlType override on column() is validated against the allowlist" {
        // Quoted/spaced/parenthesised variants accepted by the regex
        SQL_TYPE_REGEX.matches("TEXT") shouldBe true
        SQL_TYPE_REGEX.matches("VARCHAR(255)") shouldBe true
        SQL_TYPE_REGEX.matches("NUMERIC(10, 2)") shouldBe true
        SQL_TYPE_REGEX.matches("DOUBLE PRECISION") shouldBe true

        // Injection attempts rejected
        SQL_TYPE_REGEX.matches("TEXT; DROP TABLE users") shouldBe false
        SQL_TYPE_REGEX.matches("TEXT'") shouldBe false
        SQL_TYPE_REGEX.matches("INT(10) CHECK (foo > 0)") shouldBe false
    }

    "explicit sqlType override rejects malicious strings at column creation" {
        shouldThrow<IllegalArgumentException> {
            object : Table<Unit>("bad") {
                @Suppress("unused")
                val v = column("c", StringCodec, sqlType = "TEXT; DROP TABLE users") { null }
                override fun fromRow(row: Row): Unit = Unit
            }
        }
    }

    "ValueClassCodec overload preserves domain type while emitting sized type" {
        // SizedTypesTable.handle was declared with the UsernameCodec overload —
        // confirm the column captured both the codec and the sqlType override.
        SizedTypesTable.handle.codec.sqlType shouldBe String::class.java
        SizedTypesTable.handle.sqlType shouldBe "VARCHAR(30)"
    }

    "money-style column captures NUMERIC(precision, scale) exactly" {
        SizedTypesTable.balance.sqlType shouldBe "NUMERIC(12, 2)"
    }

    "ergonomic builders compose with checks, isPrimaryKey, isNullable" {
        ComposedTable.createTableSql(PostgresDialect) shouldContain
            "\"slug\" VARCHAR(50) NOT NULL"
        ComposedTable.createTableSql(PostgresDialect) shouldContain
            "CONSTRAINT \"chk_composed_slug\" CHECK"
    }

})

private object ProfilesTable : Table<Unit>("profiles") {
    @Suppress("unused")
    val pid    = tsid("id", isPrimaryKey = true) { null }
    @Suppress("unused")
    val altId  = ulid("alt_id") { null }
    override fun fromRow(row: Row): Unit = Unit
}

private object ComposedTable : Table<Unit>("composed") {
    @Suppress("unused")
    val pid  = uuid("id", isPrimaryKey = true) { null }
    @Suppress("unused")
    val slug = varchar("slug", length = 50, check = com.aggitech.aggo.schema.Checks.notBlank()) { null }
    override fun fromRow(row: Row): Unit = Unit
}
