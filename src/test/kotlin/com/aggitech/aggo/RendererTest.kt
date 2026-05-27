package com.aggitech.aggo

import com.aggitech.aggo.dialect.MySqlDialect
import com.aggitech.aggo.dialect.OracleDialect
import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.dialect.forSchema
import com.aggitech.aggo.dsl.aggregate
import com.aggitech.aggo.dsl.and
import com.aggitech.aggo.dsl.avg
import com.aggitech.aggo.dsl.between
import com.aggitech.aggo.dsl.coalesce
import com.aggitech.aggo.dsl.count
import com.aggitech.aggo.dsl.countDistinct
import com.aggitech.aggo.dsl.countStar
import com.aggitech.aggo.dsl.delete
import com.aggitech.aggo.dsl.eq
import com.aggitech.aggo.dsl.gt
import com.aggitech.aggo.dsl.gte
import com.aggitech.aggo.dsl.inList
import com.aggitech.aggo.dsl.insert
import com.aggitech.aggo.dsl.ilike
import com.aggitech.aggo.dsl.isNotNull
import com.aggitech.aggo.dsl.leftJoin
import com.aggitech.aggo.dsl.limit
import com.aggitech.aggo.dsl.like
import com.aggitech.aggo.dsl.lower
import com.aggitech.aggo.dsl.minus
import com.aggitech.aggo.dsl.matchesRegex
import com.aggitech.aggo.dsl.matchesRegexIgnoreCase
import com.aggitech.aggo.dsl.notIlike
import com.aggitech.aggo.dsl.notMatchesRegex
import com.aggitech.aggo.dsl.orderBy
import com.aggitech.aggo.dsl.or
import com.aggitech.aggo.dsl.plus
import com.aggitech.aggo.dsl.select
import com.aggitech.aggo.dsl.sum
import com.aggitech.aggo.dsl.times
import com.aggitech.aggo.dsl.update
import com.aggitech.aggo.dsl.upper
import com.aggitech.aggo.dsl.where
import com.aggitech.aggo.migration.migrationPlan
import com.aggitech.aggo.migration.migrationSchema
import com.aggitech.aggo.render.renderAggregateSelect
import com.aggitech.aggo.render.renderCountSelect
import com.aggitech.aggo.render.renderDelete
import com.aggitech.aggo.render.renderInsert
import com.aggitech.aggo.render.renderJoinSelect
import com.aggitech.aggo.render.renderProjectionSelect
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

    "count select reuses filter and ignores order limit offset" {
        val q = select(People) {
            where { People.active eq true }
            orderBy { People.createdAt.desc() }
            limit(10)
            offset(20)
        }
        val r = renderCountSelect(q, PostgresDialect)
        r.sql shouldBe """SELECT COUNT(*) FROM "people" WHERE "people"."active" = ${'$'}1"""
        r.params shouldHaveSize 1
        r.params[0].value shouldBe true
    }

    "schema-qualified dialect renders table references across select forms" {
        val dialect = PostgresDialect.forSchema("acme")
        val select = renderSelect(
            select(People) {
                where { People.active eq true }
                orderBy { People.createdAt.desc() }
            },
            dialect,
        )
        select.sql shouldBe
            """SELECT "id", "email", "name", "active", "created_at" FROM "acme"."people" """ +
            """WHERE "acme"."people"."active" = ${'$'}1 """ +
            """ORDER BY "acme"."people"."created_at" DESC"""

        val projection = renderProjectionSelect(
            com.aggitech.aggo.dsl.selectProjection(People, People.id, People.email) {
                where { People.email.isNotNull() }
            },
            dialect,
        )
        projection.sql shouldBe
            """SELECT "id", "email" FROM "acme"."people" WHERE "acme"."people"."email" IS NOT NULL"""

        val aggregate = renderAggregateSelect(
            aggregate(People) {
                project(count(People.id) `as` "cnt")
                groupBy(People.active)
            },
            dialect,
        )
        aggregate.sql shouldBe
            """SELECT COUNT("acme"."people"."id") AS "cnt" FROM "acme"."people" """ +
            """GROUP BY "acme"."people"."active""""
    }

    "schema-qualified dialect renders joins and mutations" {
        val dialect = PostgresDialect.forSchema("acme")
        val joined = renderJoinSelect(People.leftJoin(Pets) { People.id eq Pets.ownerId }, dialect)
        joined.sql shouldBe
            """SELECT "acme"."people"."id", "acme"."people"."email", "acme"."people"."name", """ +
            """"acme"."people"."active", "acme"."people"."created_at", "acme"."pets"."id", """ +
            """"acme"."pets"."owner_id", "acme"."pets"."name" FROM "acme"."people" """ +
            """LEFT JOIN "acme"."pets" ON "acme"."people"."id" = "acme"."pets"."owner_id""""

        renderInsert(insert(People) { People.email setTo Email("a@b.com") }, dialect).sql shouldBe
            """INSERT INTO "acme"."people" ("email") VALUES (${'$'}1)"""
        renderUpdate(
            update(People) {
                People.active setTo false
                where { People.id eq 1 }
            },
            dialect,
        ).sql shouldBe
            """UPDATE "acme"."people" SET "active" = ${'$'}1 WHERE "acme"."people"."id" = ${'$'}2"""
        renderDelete(delete(People) { where { People.id eq 1 } }, dialect).sql shouldBe
            """DELETE FROM "acme"."people" WHERE "acme"."people"."id" = ${'$'}1"""
    }

    "schema-qualified migration dialect renders DDL table references" {
        val dialect = PostgresDialect.forSchema("acme")
        val plan = migrationPlan(
            migrationSchema("spec_9_schema", listOf(People, Pets), dialect),
            dialect,
        )

        plan.steps.mapNotNull { it.sql } shouldBe listOf(
            """
            CREATE TABLE "acme"."people" (
                "id" INTEGER NOT NULL,
                "email" TEXT NOT NULL,
                "name" TEXT NOT NULL,
                "active" BOOLEAN NOT NULL,
                "created_at" TIMESTAMPTZ NOT NULL,
                PRIMARY KEY ("id")
            );
            """.trimIndent(),
            """
            CREATE TABLE "acme"."pets" (
                "id" INTEGER NOT NULL,
                "owner_id" INTEGER NOT NULL,
                "name" TEXT NOT NULL,
                PRIMARY KEY ("id")
            );
            """.trimIndent(),
        )
    }

    "select renders MySQL placeholders quotes and pagination" {
        val q = select(People) {
            where { People.fullName ilike "%ann%" }
            orderBy { People.fullName.asc() }
            limit(10)
            offset(20)
        }
        val r = renderSelect(q, MySqlDialect)
        r.sql shouldBe
            "SELECT `id`, `email`, `name`, `active`, `created_at` FROM `people` " +
            "WHERE `people`.`name` LIKE ? " +
            "ORDER BY `people`.`name` ASC " +
            "LIMIT 10 OFFSET 20"
        r.params.map { it.value } shouldBe listOf("%ann%")
    }

    "select renders Oracle placeholders quotes and pagination" {
        val q = select(People) {
            where { People.fullName matchesRegexIgnoreCase "^ann" }
            orderBy { People.fullName.asc() }
            limit(10)
            offset(20)
        }
        val r = renderSelect(q, OracleDialect)
        r.sql shouldBe
            """SELECT "id", "email", "name", "active", "created_at" FROM "people" """ +
            """WHERE REGEXP_LIKE("people"."name", :1, 'i') """ +
            """ORDER BY "people"."name" ASC """ +
            """OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY"""
        r.params.map { it.value } shouldBe listOf("^ann")
    }

    "ilike predicate renders for each dialect" {
        val q = select(People) { where { People.fullName ilike "%alice%" } }
        renderSelect(q, PostgresDialect).sql shouldBe
            """SELECT "id", "email", "name", "active", "created_at" FROM "people" WHERE "people"."name" ILIKE ${'$'}1"""
        renderSelect(q, MySqlDialect).sql shouldBe
            "SELECT `id`, `email`, `name`, `active`, `created_at` FROM `people` WHERE `people`.`name` LIKE ?"
        renderSelect(q, OracleDialect).sql shouldBe
            """SELECT "id", "email", "name", "active", "created_at" FROM "people" WHERE REGEXP_LIKE("people"."name", :1, 'i')"""
    }

    "not ilike predicate renders for each dialect" {
        val q = select(People) { where { People.fullName notIlike "%alice%" } }
        renderSelect(q, PostgresDialect).sql shouldBe
            """SELECT "id", "email", "name", "active", "created_at" FROM "people" WHERE "people"."name" NOT ILIKE ${'$'}1"""
        renderSelect(q, MySqlDialect).sql shouldBe
            "SELECT `id`, `email`, `name`, `active`, `created_at` FROM `people` WHERE `people`.`name` NOT LIKE ?"
        renderSelect(q, OracleDialect).sql shouldBe
            """SELECT "id", "email", "name", "active", "created_at" FROM "people" WHERE NOT REGEXP_LIKE("people"."name", :1, 'i')"""
    }

    "regex predicate renders for each dialect" {
        val q = select(People) { where { People.fullName matchesRegex "^A" } }
        renderSelect(q, PostgresDialect).sql shouldBe
            """SELECT "id", "email", "name", "active", "created_at" FROM "people" WHERE "people"."name" ~ ${'$'}1"""
        renderSelect(q, MySqlDialect).sql shouldBe
            "SELECT `id`, `email`, `name`, `active`, `created_at` FROM `people` WHERE `people`.`name` REGEXP ?"
        renderSelect(q, OracleDialect).sql shouldBe
            """SELECT "id", "email", "name", "active", "created_at" FROM "people" WHERE REGEXP_LIKE("people"."name", :1)"""
    }

    "negated regex predicate renders for each dialect" {
        val q = select(People) { where { People.fullName notMatchesRegex "^A" } }
        renderSelect(q, PostgresDialect).sql shouldBe
            """SELECT "id", "email", "name", "active", "created_at" FROM "people" WHERE "people"."name" !~ ${'$'}1"""
        renderSelect(q, MySqlDialect).sql shouldBe
            "SELECT `id`, `email`, `name`, `active`, `created_at` FROM `people` WHERE `people`.`name` NOT REGEXP ?"
        renderSelect(q, OracleDialect).sql shouldBe
            """SELECT "id", "email", "name", "active", "created_at" FROM "people" WHERE NOT REGEXP_LIKE("people"."name", :1)"""
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

    // ── Expression operators ────────────────────────────────────────────────

    "arithmetic column + literal renders parenthesised binary expr in WHERE" {
        val q = select(People) { where { (People.id + 10) gt 20 } }
        val r = renderSelect(q, PostgresDialect)
        r.sql shouldBe
            """SELECT "id", "email", "name", "active", "created_at" FROM "people" """ +
            """WHERE ("people"."id" + ${'$'}1) > ${'$'}2"""
        r.params.map { it.value } shouldBe listOf(10, 20)
    }

    "arithmetic column - column renders subtraction expr in WHERE" {
        val q = select(Pets) { where { (Pets.id - Pets.ownerId) gt 0 } }
        val r = renderSelect(q, PostgresDialect)
        r.sql shouldBe
            """SELECT "id", "owner_id", "name" FROM "pets" """ +
            """WHERE ("pets"."id" - "pets"."owner_id") > ${'$'}1"""
        r.params.map { it.value } shouldBe listOf(0)
    }

    "string column + literal renders SQL || concatenation in WHERE" {
        val q = select(People) { where { (People.fullName + "!") eq "Alice!" } }
        val r = renderSelect(q, PostgresDialect)
        r.sql shouldBe
            """SELECT "id", "email", "name", "active", "created_at" FROM "people" """ +
            """WHERE ("people"."name" || ${'$'}1) = ${'$'}2"""
        r.params.map { it.value } shouldBe listOf("!", "Alice!")
    }

    "string column + column renders SQL || between two columns" {
        val q = select(People) { where { (People.fullName + People.fullName) eq "AliceAlice" } }
        val r = renderSelect(q, PostgresDialect)
        r.sql shouldBe
            """SELECT "id", "email", "name", "active", "created_at" FROM "people" """ +
            """WHERE ("people"."name" || "people"."name") = ${'$'}1"""
    }

    "upper function renders UPPER() in WHERE" {
        val q = select(People) { where { upper(People.fullName) eq "ALICE" } }
        val r = renderSelect(q, PostgresDialect)
        r.sql shouldBe
            """SELECT "id", "email", "name", "active", "created_at" FROM "people" """ +
            """WHERE UPPER("people"."name") = ${'$'}1"""
        r.params.map { it.value } shouldBe listOf("ALICE")
    }

    "lower function renders LOWER() in WHERE" {
        val q = select(People) { where { lower(People.fullName) eq "alice" } }
        val r = renderSelect(q, PostgresDialect)
        r.sql shouldBe
            """SELECT "id", "email", "name", "active", "created_at" FROM "people" """ +
            """WHERE LOWER("people"."name") = ${'$'}1"""
    }

    "coalesce column + fallback renders COALESCE() in WHERE" {
        val q = select(People) { where { coalesce(People.fullName, "unknown") eq "unknown" } }
        val r = renderSelect(q, PostgresDialect)
        r.sql shouldBe
            """SELECT "id", "email", "name", "active", "created_at" FROM "people" """ +
            """WHERE COALESCE("people"."name", ${'$'}1) = ${'$'}2"""
        r.params.map { it.value } shouldBe listOf("unknown", "unknown")
    }

    // ── Aggregate SELECT ────────────────────────────────────────────────────

    "aggregate with countStar renders COUNT(*) projection" {
        val cnt = countStar() `as` "cnt"
        val q = aggregate(People) { project(cnt) }
        val r = renderAggregateSelect(q, PostgresDialect)
        r.sql shouldBe """SELECT COUNT(*) AS "cnt" FROM "people""""
        r.params shouldHaveSize 0
    }

    "aggregate with count column renders COUNT projection" {
        val cnt = count(People.id) `as` "cnt"
        val q = aggregate(People) { project(cnt) }
        val r = renderAggregateSelect(q, PostgresDialect)
        r.sql shouldBe """SELECT COUNT("people"."id") AS "cnt" FROM "people""""
    }

    "aggregate with countDistinct renders COUNT(DISTINCT ...)" {
        val cnt = countDistinct(People.fullName) `as` "unique_names"
        val q = aggregate(People) { project(cnt) }
        val r = renderAggregateSelect(q, PostgresDialect)
        r.sql shouldBe """SELECT COUNT(DISTINCT "people"."name") AS "unique_names" FROM "people""""
    }

    "aggregate with sum and groupBy renders full GROUP BY query" {
        val total = sum(People.id) `as` "total"
        val q = aggregate(People) {
            project(total)
            groupBy(People.active)
        }
        val r = renderAggregateSelect(q, PostgresDialect)
        r.sql shouldBe
            """SELECT SUM("people"."id") AS "total" FROM "people" """ +
            """GROUP BY "people"."active""""
    }

    "aggregate with avg renders AVG() projection" {
        val mean = avg(People.id) `as` "mean"
        val q = aggregate(People) { project(mean) }
        val r = renderAggregateSelect(q, PostgresDialect)
        r.sql shouldBe """SELECT AVG("people"."id") AS "mean" FROM "people""""
    }

    "aggregate with where and having renders both clauses" {
        val cnt = count(People.id) `as` "cnt"
        val q = aggregate(People) {
            project(cnt)
            where { People.active eq true }
            groupBy(People.active)
            having { count(People.id) gt 0L }
        }
        val r = renderAggregateSelect(q, PostgresDialect)
        r.sql shouldBe
            """SELECT COUNT("people"."id") AS "cnt" FROM "people" """ +
            """WHERE "people"."active" = ${'$'}1 """ +
            """GROUP BY "people"."active" """ +
            """HAVING COUNT("people"."id") > ${'$'}2"""
        r.params.map { it.value } shouldBe listOf(true, 0L)
    }

    "aggregate with orderBy and limit renders ORDER BY + LIMIT" {
        val cnt = countStar() `as` "cnt"
        val q = aggregate(People) {
            project(cnt)
            groupBy(People.active)
            orderBy { cnt.desc() }
            limit(10)
        }
        val r = renderAggregateSelect(q, PostgresDialect)
        r.sql shouldBe
            """SELECT COUNT(*) AS "cnt" FROM "people" """ +
            """GROUP BY "people"."active" """ +
            """ORDER BY COUNT(*) DESC """ +
            """LIMIT 10"""
    }

    "aggregate with multiple projections renders all columns" {
        val cnt = count(People.id) `as` "cnt"
        val total = sum(People.id) `as` "total"
        val q = aggregate(People) {
            project(cnt)
            project(total)
        }
        val r = renderAggregateSelect(q, PostgresDialect)
        r.sql shouldBe
            """SELECT COUNT("people"."id") AS "cnt", SUM("people"."id") AS "total" FROM "people""""
    }

    "chained arithmetic expr renders nested parentheses" {
        // (id + 1) * 2
        val q = select(People) { where { ((People.id + 1) * 2) gt 10 } }
        val r = renderSelect(q, PostgresDialect)
        r.sql shouldBe
            """SELECT "id", "email", "name", "active", "created_at" FROM "people" """ +
            """WHERE (("people"."id" + ${'$'}1) * ${'$'}2) > ${'$'}3"""
        r.params.map { it.value } shouldBe listOf(1, 2, 10)
    }
})
