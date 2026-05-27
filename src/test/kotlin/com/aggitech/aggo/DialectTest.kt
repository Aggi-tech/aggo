package com.aggitech.aggo

import com.aggitech.aggo.dialect.InsertReturnStrategy
import com.aggitech.aggo.dialect.MySqlDialect
import com.aggitech.aggo.dialect.OracleDialect
import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.dialect.decorators.withOnConflict
import com.aggitech.aggo.dialect.decorators.withOnDuplicateKey
import com.aggitech.aggo.dsl.insert
import com.aggitech.aggo.schema.BigDecimalCodec
import com.aggitech.aggo.schema.BooleanCodec
import com.aggitech.aggo.schema.ByteArrayCodec
import com.aggitech.aggo.schema.InstantCodec
import com.aggitech.aggo.schema.IntCodec
import com.aggitech.aggo.schema.StringCodec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class DialectTest : StringSpec({

    "placeholder is 1-based and dollar-prefixed" {
        PostgresDialect.placeholder(1) shouldBe "\$1"
        PostgresDialect.placeholder(42) shouldBe "\$42"
        MySqlDialect.placeholder(1) shouldBe "?"
        OracleDialect.placeholder(42) shouldBe ":42"
    }

    "quoteIdentifier accepts valid identifiers" {
        PostgresDialect.quoteIdentifier("payers") shouldBe "\"payers\""
        MySqlDialect.quoteIdentifier("payers") shouldBe "`payers`"
        OracleDialect.quoteIdentifier("payers") shouldBe "\"payers\""
        PostgresDialect.quoteIdentifier("first_name") shouldBe "\"first_name\""
        PostgresDialect.quoteIdentifier("_underscore_start") shouldBe "\"_underscore_start\""
        PostgresDialect.quoteIdentifier("Col1") shouldBe "\"Col1\""
    }

    "quoteIdentifier rejects SQL injection attempts" {
        shouldThrow<IllegalArgumentException> {
            PostgresDialect.quoteIdentifier("payers; DROP TABLE x")
        }
        shouldThrow<IllegalArgumentException> {
            PostgresDialect.quoteIdentifier("col\"; DROP --")
        }
        shouldThrow<IllegalArgumentException> {
            PostgresDialect.quoteIdentifier("'; SELECT 1")
        }
        shouldThrow<IllegalArgumentException> {
            PostgresDialect.quoteIdentifier(" with space ")
        }
        shouldThrow<IllegalArgumentException> {
            PostgresDialect.quoteIdentifier("")
        }
        shouldThrow<IllegalArgumentException> {
            // longer than Postgres NAMEDATALEN-1
            PostgresDialect.quoteIdentifier("a".repeat(64))
        }
        shouldThrow<IllegalArgumentException> {
            // starts with digit
            PostgresDialect.quoteIdentifier("9bad")
        }
    }

    "pagination renders per dialect" {
        PostgresDialect.renderPagination(null, null) shouldBe ""
        PostgresDialect.renderPagination(10, null) shouldBe "LIMIT 10"
        PostgresDialect.renderPagination(null, 20) shouldBe "OFFSET 20"
        PostgresDialect.renderPagination(10, 20) shouldBe "LIMIT 10 OFFSET 20"

        MySqlDialect.renderPagination(null, null) shouldBe ""
        MySqlDialect.renderPagination(10, null) shouldBe "LIMIT 10"
        MySqlDialect.renderPagination(null, 20) shouldBe "LIMIT 18446744073709551615 OFFSET 20"
        MySqlDialect.renderPagination(10, 20) shouldBe "LIMIT 10 OFFSET 20"

        OracleDialect.renderPagination(null, null) shouldBe ""
        OracleDialect.renderPagination(10, null) shouldBe "FETCH NEXT 10 ROWS ONLY"
        OracleDialect.renderPagination(null, 20) shouldBe "OFFSET 20 ROWS"
        OracleDialect.renderPagination(10, 20) shouldBe "OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY"
    }

    "case-insensitive like renders per dialect" {
        PostgresDialect.renderLikeIgnoreCase("\"name\"", "\$1", false) shouldBe "\"name\" ILIKE \$1"
        PostgresDialect.renderLikeIgnoreCase("\"name\"", "\$1", true) shouldBe "\"name\" NOT ILIKE \$1"
        MySqlDialect.renderLikeIgnoreCase("`name`", "?", false) shouldBe "`name` LIKE ?"
        MySqlDialect.renderLikeIgnoreCase("`name`", "?", true) shouldBe "`name` NOT LIKE ?"
        OracleDialect.renderLikeIgnoreCase("\"name\"", ":1", false) shouldBe "REGEXP_LIKE(\"name\", :1, 'i')"
        OracleDialect.renderLikeIgnoreCase("\"name\"", ":1", true) shouldBe "NOT REGEXP_LIKE(\"name\", :1, 'i')"
    }

    "regex match renders per dialect" {
        PostgresDialect.renderRegexMatch("\"name\"", "\$1", false, false) shouldBe "\"name\" ~ \$1"
        PostgresDialect.renderRegexMatch("\"name\"", "\$1", true, false) shouldBe "\"name\" ~* \$1"
        PostgresDialect.renderRegexMatch("\"name\"", "\$1", false, true) shouldBe "\"name\" !~ \$1"
        PostgresDialect.renderRegexMatch("\"name\"", "\$1", true, true) shouldBe "\"name\" !~* \$1"

        MySqlDialect.renderRegexMatch("`name`", "?", false, false) shouldBe "`name` REGEXP ?"
        MySqlDialect.renderRegexMatch("`name`", "?", true, false) shouldBe "`name` REGEXP ?"
        MySqlDialect.renderRegexMatch("`name`", "?", false, true) shouldBe "`name` NOT REGEXP ?"
        MySqlDialect.renderRegexMatch("`name`", "?", true, true) shouldBe "`name` NOT REGEXP ?"

        OracleDialect.renderRegexMatch("\"name\"", ":1", false, false) shouldBe "REGEXP_LIKE(\"name\", :1)"
        OracleDialect.renderRegexMatch("\"name\"", ":1", true, false) shouldBe "REGEXP_LIKE(\"name\", :1, 'i')"
        OracleDialect.renderRegexMatch("\"name\"", ":1", false, true) shouldBe "NOT REGEXP_LIKE(\"name\", :1)"
        OracleDialect.renderRegexMatch("\"name\"", ":1", true, true) shouldBe "NOT REGEXP_LIKE(\"name\", :1, 'i')"
    }

    "insert return strategies render clauses per dialect" {
        PostgresDialect.insertReturnStrategy(listOf(People.id)) shouldBe
            InsertReturnStrategy.AppendClause("""RETURNING "id"""")

        MySqlDialect.insertReturnStrategy(listOf(People.id)) shouldBe
            InsertReturnStrategy.PostInsertSelect("SELECT `id` FROM `people` WHERE `id` = ?")

        OracleDialect.insertReturnStrategy(listOf(People.id)) shouldBe
            InsertReturnStrategy.ReturningInto(
                """RETURNING "id" INTO :out1""",
                listOf(InsertReturnStrategy.OutParam(":out1", Int::class.javaObjectType)),
            )
    }

    "column SQL types render per dialect" {
        MySqlDialect.columnSqlType(StringCodec) shouldBe "LONGTEXT"
        MySqlDialect.columnSqlType(IntCodec) shouldBe "INT"
        MySqlDialect.columnSqlType(BooleanCodec) shouldBe "TINYINT(1)"
        MySqlDialect.columnSqlType(BigDecimalCodec) shouldBe "DECIMAL(38, 4)"
        MySqlDialect.columnSqlType(InstantCodec) shouldBe "DATETIME"
        MySqlDialect.columnSqlType(ByteArrayCodec) shouldBe "LONGBLOB"

        OracleDialect.columnSqlType(StringCodec) shouldBe "VARCHAR2(4000)"
        OracleDialect.columnSqlType(IntCodec) shouldBe "NUMBER(10)"
        OracleDialect.columnSqlType(BooleanCodec) shouldBe "NUMBER(1)"
        OracleDialect.columnSqlType(BigDecimalCodec) shouldBe "NUMBER(38, 4)"
        OracleDialect.columnSqlType(InstantCodec) shouldBe "TIMESTAMP WITH TIME ZONE"
        OracleDialect.columnSqlType(ByteArrayCodec) shouldBe "BLOB"
    }

    "vendor decorators render extension clauses" {
        val q = insert(People) {
            People.email setTo Email("a@b.com")
            People.fullName setTo "Anna"
        }
        val assignments = q.assignments

        PostgresDialect.withOnConflict()
            .renderOnConflictDoNothing(listOf(People.email)) shouldBe
            """ON CONFLICT ("email") DO NOTHING"""

        PostgresDialect.withOnConflict()
            .renderOnConflictUpdate(listOf(People.email), assignments) shouldBe
            """ON CONFLICT ("email") DO UPDATE SET "email" = EXCLUDED."email", "name" = EXCLUDED."name""""

        MySqlDialect.withOnDuplicateKey()
            .renderOnDuplicateKeyUpdate(assignments) shouldBe
            "ON DUPLICATE KEY UPDATE `email` = VALUES(`email`), `name` = VALUES(`name`)"
    }
})
