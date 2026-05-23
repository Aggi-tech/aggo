package com.aggitech.aggo

import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.dsl.and
import com.aggitech.aggo.dsl.between
import com.aggitech.aggo.dsl.delete
import com.aggitech.aggo.dsl.eq
import com.aggitech.aggo.dsl.gte
import com.aggitech.aggo.dsl.inList
import com.aggitech.aggo.dsl.insert
import com.aggitech.aggo.dsl.isNotNull
import com.aggitech.aggo.dsl.leftJoin
import com.aggitech.aggo.dsl.limit
import com.aggitech.aggo.dsl.like
import com.aggitech.aggo.dsl.orderBy
import com.aggitech.aggo.dsl.or
import com.aggitech.aggo.dsl.select
import com.aggitech.aggo.dsl.update
import com.aggitech.aggo.dsl.where
import com.aggitech.aggo.render.renderDelete
import com.aggitech.aggo.render.renderInsert
import com.aggitech.aggo.render.renderJoinSelect
import com.aggitech.aggo.render.renderSelect
import com.aggitech.aggo.render.renderUpdate
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Instant

class RendererTest : StringSpec({

    "select renders all columns by default with quoted identifiers" {
        val q = select(People)
        val r = renderSelect(q, PostgresDialect)
        r.sql shouldBe """SELECT "id", "email", "name", "active", "created_at" FROM "people""""
        r.params shouldHaveSize 0
    }

    "select with where uses positional dollar placeholders (R2DBC Postgres style)" {
        val q = select(People) {
            where { (People.active eq true) and (People.fullName like "%a%") }
        }
        val r = renderSelect(q, PostgresDialect)
        r.sql shouldBe
            """SELECT "id", "email", "name", "active", "created_at" FROM "people" """ +
            """WHERE ("people"."active" = ${'$'}1 AND "people"."name" LIKE ${'$'}2)"""
        r.params shouldHaveSize 2
        r.params[0].value shouldBe true
        r.params[1].value shouldBe "%a%"
    }

    "select with order/limit/offset" {
        val q = select(People) {
            where { People.active eq true }
            orderBy {
                People.createdAt.desc()
                People.fullName.asc()
            }
            limit(10)
            offset(20)
        }
        val r = renderSelect(q, PostgresDialect)
        r.sql shouldBe
            """SELECT "id", "email", "name", "active", "created_at" FROM "people" """ +
            """WHERE "people"."active" = ${'$'}1 """ +
            """ORDER BY "people"."created_at" DESC, "people"."name" ASC """ +
            """LIMIT 10 OFFSET 20"""
    }

    "select with IN list, BETWEEN, OR" {
        val q = select(People) {
            where {
                (People.id inList listOf(1, 2, 3)) or
                    (People.id.between(10, 20)) or
                    People.email.isNotNull()
            }
        }
        val r = renderSelect(q, PostgresDialect)
        r.sql shouldBe
            """SELECT "id", "email", "name", "active", "created_at" FROM "people" """ +
            """WHERE (("people"."id" IN (${'$'}1, ${'$'}2, ${'$'}3) OR """ +
            """"people"."id" BETWEEN ${'$'}4 AND ${'$'}5) OR """ +
            """"people"."email" IS NOT NULL)"""
        r.params.map { it.value } shouldBe listOf(1, 2, 3, 10, 20)
    }

    "select with IN of empty list emits a tautology" {
        val q = select(People) { where { People.id inList emptyList() } }
        val r = renderSelect(q, PostgresDialect)
        r.sql shouldBe """SELECT "id", "email", "name", "active", "created_at" FROM "people" WHERE 1 = 0"""
    }

    "select with limit gte 1M is allowed; out of range throws" {
        // limit 0 and 10M are allowed
        renderSelect(select(People) { limit(0) }, PostgresDialect)
        renderSelect(select(People) { limit(10_000_000) }, PostgresDialect)

        try {
            select(People) { limit(-1) }
            error("should have thrown")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    "leftJoin renders qualified table columns in positional mapping order" {
        val q = People.leftJoin(Pets) { People.id eq Pets.ownerId }
            .where { People.active eq true }
            .orderBy {
                Pets.petName.asc()
                People.id.desc()
            }
            .limit(25)

        val r = renderJoinSelect(q, PostgresDialect)

        r.sql shouldBe
            """SELECT "people"."id", "people"."email", "people"."name", "people"."active", "people"."created_at", """ +
            """"pets"."id", "pets"."owner_id", "pets"."name" """ +
            """FROM "people" LEFT JOIN "pets" ON "people"."id" = "pets"."owner_id" """ +
            """WHERE "people"."active" = ${'$'}1 """ +
            """ORDER BY "pets"."name" ASC, "people"."id" DESC """ +
            """LIMIT 25"""
        r.params.map { it.value } shouldBe listOf(true)
    }

    "insert with explicit assignments + ValueClassCodec unwraps inline class" {
        val q = insert(People) {
            People.email setTo Email("a@b.com")
            People.fullName setTo "Anna"
            People.active setTo true
        }
        val r = renderInsert(q, PostgresDialect)
        r.sql shouldBe """INSERT INTO "people" ("email", "name", "active") VALUES (${'$'}1, ${'$'}2, ${'$'}3)"""
        // Bound preserves the wrapper; Binder will call codec.encode() at bind time.
        r.params.map { it.value } shouldBe listOf(Email("a@b.com"), "Anna", true)
    }

    "insert(table, entity) only emits writable (non-generated) columns" {
        val person = Person(
            id = 99,
            email = Email("x@y"),
            name = "X",
            active = false,
            createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        )
        val q = insert(People, person)
        val r = renderInsert(q, PostgresDialect)
        // id and created_at are isGenerated=true → skipped
        r.sql shouldBe """INSERT INTO "people" ("email", "name", "active") VALUES (${'$'}1, ${'$'}2, ${'$'}3)"""
    }

    "insert RETURNING includes primary keys" {
        val q = insert(People) {
            People.email setTo Email("a@b.com")
            People.fullName setTo "Anna"
            People.active setTo true
        }
        val r = renderInsert(q, PostgresDialect, returningPk = true)
        r.sql shouldBe
            """INSERT INTO "people" ("email", "name", "active") VALUES (${'$'}1, ${'$'}2, ${'$'}3) RETURNING "id""""
    }

    "update renders SET and WHERE with dialect placeholders" {
        val q = update(People) {
            People.active setTo false
            where { People.id eq 7 }
        }
        val r = renderUpdate(q, PostgresDialect)
        r.sql shouldBe """UPDATE "people" SET "active" = ${'$'}1 WHERE "people"."id" = ${'$'}2"""
        r.params.map { it.value } shouldBe listOf(false, 7)
    }

    "delete renders WHERE; without WHERE renders bare DELETE" {
        val r1 = renderDelete(delete(People) { where { People.id gte 100 } }, PostgresDialect)
        r1.sql shouldBe """DELETE FROM "people" WHERE "people"."id" >= ${'$'}1"""

        val r2 = renderDelete(delete(People), PostgresDialect)
        r2.sql shouldBe """DELETE FROM "people""""
    }
})
