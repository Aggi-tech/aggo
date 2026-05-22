package com.aggitech.aggo

import com.aggitech.aggo.dialect.PostgresDialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class DialectTest : StringSpec({

    "placeholder is 1-based and dollar-prefixed" {
        PostgresDialect.placeholder(1) shouldBe "\$1"
        PostgresDialect.placeholder(42) shouldBe "\$42"
    }

    "quoteIdentifier accepts valid identifiers" {
        PostgresDialect.quoteIdentifier("payers") shouldBe "\"payers\""
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
})
