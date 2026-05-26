package com.aggitech.aggo

import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.migration.createTableSql
import com.aggitech.aggo.migration.migrationSchema
import com.aggitech.aggo.schema.Checks
import com.aggitech.aggo.schema.DomainType
import com.aggitech.aggo.schema.IntCodec
import com.aggitech.aggo.schema.StringCodec
import com.aggitech.aggo.schema.Table
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.r2dbc.spi.Row

@JvmInline value class Slug(val raw: String)
@JvmInline value class PositiveCount(val raw: Int)

private val SlugCodec = DomainType.create(
    name = "slug_domain",
    base = StringCodec,
    sqlBaseType = "VARCHAR(64)",
    wrap = ::Slug,
    unwrap = Slug::raw,
    checks = listOf(Checks.notBlank(), Checks.length(min = 3, max = 64)),
)

private val PositiveCountCodec = DomainType.create(
    name = "positive_count",
    base = IntCodec,
    sqlBaseType = "INTEGER",
    wrap = ::PositiveCount,
    unwrap = PositiveCount::raw,
    checks = listOf(Checks.positive()),
    createIfMissing = false,
)

private object PostsTable : Table<Unit>("posts") {
    @Suppress("unused")
    val pid   = uuid("id", isPrimaryKey = true) { null }
    @Suppress("unused")
    val slug  = column("slug", SlugCodec) { null }
    @Suppress("unused")
    val views = column("views", PositiveCountCodec) { null }
    override fun fromRow(row: Row): Unit = Unit
}

class DomainTypeTest : StringSpec({

    "DomainType.create wraps the base codec like a ValueClassCodec" {
        val encoded = SlugCodec.encode(Slug("hello"))
        encoded shouldBe "hello"
        SlugCodec.decode("world") shouldBe Slug("world")
        SlugCodec.encode(null) shouldBe null
        SlugCodec.decode(null) shouldBe null
    }

    "DomainType.create exposes the underlying driver sqlType" {
        SlugCodec.sqlType shouldBe String::class.java
        PositiveCountCodec.sqlType shouldBe Int::class.javaObjectType
    }

    "DomainType.create generates CREATE DOMAIN DDL with VALUE-keyword CHECK" {
        val ddl = SlugCodec.createDdl!!
        ddl shouldContain "CREATE DOMAIN \"slug_domain\" AS VARCHAR(64)"
        ddl shouldContain "CHECK ("
        // VALUE must be the bare keyword, not the quoted identifier "\"VALUE\""
        ddl shouldContain "trim(VALUE)"
        ddl shouldContain "char_length(VALUE) >= 3"
        ddl shouldContain "char_length(VALUE) <= 64"
    }

    "createIfMissing=true wraps the DDL in DO ... duplicate_object guard" {
        SlugCodec.createDdl!! shouldContain "duplicate_object"
    }

    "createIfMissing=false emits the bare CREATE DOMAIN" {
        val ddl = PositiveCountCodec.createDdl!!
        ddl shouldContain "CREATE DOMAIN \"positive_count\" AS INTEGER CHECK"
        (ddl.contains("duplicate_object")) shouldBe false
    }

    "single-check DOMAIN inlines the expression without enclosing parens" {
        PositiveCountCodec.createDdl!! shouldContain "CHECK (VALUE > 0)"
    }

    "DomainType.create with no checks is rejected" {
        shouldThrow<IllegalArgumentException> {
            DomainType.create(
                name = "empty_domain",
                base = StringCodec,
                sqlBaseType = "TEXT",
                wrap = { it },
                unwrap = { it },
                checks = emptyList(),
            )
        }
    }

    "DomainType.create rejects invalid type names (injection guard)" {
        shouldThrow<IllegalArgumentException> {
            DomainType.create(
                name = "evil; DROP TABLE x",
                base = StringCodec,
                sqlBaseType = "TEXT",
                wrap = { it },
                unwrap = { it },
                checks = listOf(Checks.notBlank()),
            )
        }
    }

    "DomainType.create rejects invalid sqlBaseType (injection guard)" {
        shouldThrow<IllegalArgumentException> {
            DomainType.create(
                name = "ok",
                base = StringCodec,
                sqlBaseType = "TEXT; DROP TABLE x",
                wrap = { it },
                unwrap = { it },
                checks = listOf(Checks.notBlank()),
            )
        }
    }

    "DomainType.createSimple builds a MigratableCodec without value-class wrapping" {
        val positive = DomainType.createSimple(
            name = "positive_simple",
            base = IntCodec,
            sqlBaseType = "INTEGER",
            checks = listOf(Checks.positive()),
        )
        positive.ddlTypeName shouldBe "positive_simple"
        positive.encode(7) shouldBe 7
        positive.decode(3) shouldBe 3
    }

    "DOMAIN type appears in migrationSchema customTypes only once even when reused" {
        val schema = migrationSchema("v1", listOf(PostsTable), PostgresDialect)
        schema.customTypes.size shouldBe 2
        schema.customTypes.map { it.name }.toSet() shouldBe setOf("slug_domain", "positive_count")
    }

    "DOMAIN column DDL references the domain name, not the base type" {
        val sql = PostsTable.createTableSql(PostgresDialect)
        sql shouldContain "\"slug\" slug_domain"
        sql shouldContain "\"views\" positive_count"
    }
})
