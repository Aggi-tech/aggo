package com.aggitech.aggo

import com.aggitech.aggo.dsl.eq
import com.aggitech.aggo.dsl.leftJoin
import com.aggitech.aggo.dsl.orderBy
import com.aggitech.aggo.dsl.where
import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.migration.MigrationFileEntry
import com.aggitech.aggo.migration.computeChecksum
import com.aggitech.aggo.migration.migrationPlan
import com.aggitech.aggo.migration.migrationSchema
import com.aggitech.aggo.runtime.Aggo
import com.aggitech.aggo.runtime.AggoPool
import com.aggitech.aggo.runtime.AggoUnsafe
import com.aggitech.aggo.runtime.PoolConfig
import com.aggitech.aggo.runtime.PostgresConfig
import com.aggitech.aggo.runtime.Query
import com.aggitech.aggo.schema.InstantCodec
import com.aggitech.aggo.schema.IntCodec
import com.aggitech.aggo.schema.StringCodec
import com.aggitech.aggo.schema.Table
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import io.r2dbc.spi.Row
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.time.Instant

private object MigrationSmokeTable : Table<Unit>("migration_smoke") {
    val id = column("id", IntCodec, isPrimaryKey = true) { null }
    val smokeName = column("name", StringCodec) { null }
    override fun fromRow(row: Row): Unit = Unit
}

private data class SchemaVersion(
    val version: String,
    val previousVersion: String?,
    val description: String,
    val appliedAt: Instant,
)

private object AggoSchemaVersions : Table<SchemaVersion>("aggo_schema_versions") {
    val version = column("version", StringCodec, isPrimaryKey = true) { it.version }
    val previousVersion = column("previous_version", StringCodec, isNullable = true) { it.previousVersion }
    val description = column("description", StringCodec) { it.description }
    val appliedAt = column("applied_at", InstantCodec, isGenerated = true) { it.appliedAt }

    override fun fromRow(row: Row): SchemaVersion = SchemaVersion(
        version = version.readRequired(row),
        previousVersion = previousVersion.read(row),
        description = description.readRequired(row),
        appliedAt = appliedAt.readRequired(row),
    )
}

/**
 * Verifica empiricamente em Postgres real (Testcontainers) os bugs do AggORM original
 * que motivaram esta lib:
 *   - C-1/C-2: transações reais (rollback funciona)
 *   - C-3: SELECT com >1 linha não trava em awaitSingle
 *   - C-4: placeholder Postgres ($N) funciona
 *   - C-5/C-6: insertReturning devolve PK tipada
 *   - C-13: bind de @JvmInline value class
 *
 * 0.2.0 — todos os call sites migrados para a API receiver-style.
 *
 * Pulado automaticamente se Docker não estiver disponível.
 */
@OptIn(AggoUnsafe::class)
class IntegrationTest : StringSpec({

    val dockerAvailable = DockerClientFactory.instance().isDockerAvailable

    lateinit var pg: PostgreSQLContainer<*>
    lateinit var aggo: Aggo

    beforeSpec {
        if (!dockerAvailable) return@beforeSpec
        @Suppress("RESOURCE_LEAK_PROBABLY")
        pg = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("aggo")
            .withUsername("aggo")
            .withPassword("aggo")
            // r2dbc-postgresql 1.0.x still links against ongres-scram 2.x for
            // SCRAM auth. Keep the vulnerable SCRAM common artifact off the
            // classpath and use md5 auth in the test-only Postgres container.
            .withEnv("POSTGRES_HOST_AUTH_METHOD", "md5")
            .withCommand("postgres", "-c", "password_encryption=md5")
        pg.start()

        aggo = Aggo(
            AggoPool.postgres(
                PostgresConfig(
                    host = pg.host,
                    port = pg.firstMappedPort,
                    database = pg.databaseName,
                    user = pg.username,
                    password = pg.password,
                    pool = PoolConfig(initialSize = 1, maxSize = 4),
                )
            )
        )

        runBlocking {
            aggo.read {
                executeRaw(
                    """
                    CREATE TABLE people (
                        id          SERIAL PRIMARY KEY,
                        email       TEXT NOT NULL,
                        name        TEXT NOT NULL,
                        active      BOOLEAN NOT NULL,
                        created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent()
                )
                executeRaw(
                    """
                    CREATE TABLE pets (
                        id          SERIAL PRIMARY KEY,
                        owner_id    INTEGER NOT NULL REFERENCES people(id),
                        name        TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }

    afterSpec {
        if (!dockerAvailable) return@afterSpec
        aggo.close()
        pg.stop()
    }

    "SELECT multi-row works (C-3, C-4)".config(enabledIf = { dockerAvailable }) {
        aggo.tx {
            insert(People) {
                People.email setTo Email("a@a"); People.fullName setTo "A"; People.active setTo true
            }
            insert(People) {
                People.email setTo Email("b@b"); People.fullName setTo "B"; People.active setTo true
            }
            insert(People) {
                People.email setTo Email("c@c"); People.fullName setTo "C"; People.active setTo false
            }
        }

        val all = aggo.fetchAll(People)
        all.size shouldBe 3
        all.map { it.name } shouldBe listOf("A", "B", "C")

        aggo.delete(People)
    }

    "LEFT JOIN maps nested objects and keeps right null when there is no match".config(enabledIf = { dockerAvailable }) {
        val annaId = aggo.insertReturning(People, People.id) {
            People.email setTo Email("anna@pets")
            People.fullName setTo "Anna"
            People.active setTo true
        }!!
        val bobId = aggo.insertReturning(People, People.id) {
            People.email setTo Email("bob@pets")
            People.fullName setTo "Bob"
            People.active setTo true
        }!!

        aggo.insert(Pets) {
            Pets.ownerId setTo annaId
            Pets.petName setTo "Nina"
        }

        val rows = aggo.read {
            fetchAllJoined(
                People.leftJoin(Pets) { People.id eq Pets.ownerId }
                    .where { People.active eq true }
                    .orderBy { People.id.asc() }
            )
        }

        rows.map { it.left.name } shouldBe listOf("Anna", "Bob")
        rows[0].right?.name shouldBe "Nina"
        rows[1].right shouldBe null

        aggo.delete(Pets)
        aggo.delete(People) { where { People.id eq annaId } }
        aggo.delete(People) { where { People.id eq bobId } }
    }

    "tx { ... } rolls back on exception (C-1, C-2)".config(enabledIf = { dockerAvailable }) {
        val countBefore = aggo.fetchAll(People).size

        shouldThrow<IllegalStateException> {
            aggo.tx {
                insert(People) {
                    People.email setTo Email("x@x"); People.fullName setTo "X"; People.active setTo true
                }
                insert(People) {
                    People.email setTo Email("y@y"); People.fullName setTo "Y"; People.active setTo true
                }
                error("boom — should rollback both inserts")
            }
        }

        val countAfter = aggo.fetchAll(People).size
        countAfter shouldBe countBefore // both inserts rolled back
    }

    "Aggo applies generated migration plans and records schema version".config(enabledIf = { dockerAvailable }) {
        val schema = migrationSchema("2026.05.25.integration", listOf(MigrationSmokeTable), PostgresDialect)
        val plan = migrationPlan(schema, PostgresDialect)

        val result = aggo.applyMigration(plan)
        result.fromVersion shouldBe null
        result.toVersion shouldBe schema.version
        result.statementsExecuted shouldBe 1

        val applied = aggo.fetchOne(AggoSchemaVersions) {
            where { AggoSchemaVersions.version eq schema.version }
        }
        applied?.previousVersion shouldBe null
        applied?.description shouldBe "create table migration_smoke"
    }

    "insertReturning decodes PK with the column codec (C-5, C-6)".config(enabledIf = { dockerAvailable }) {
        val newId: Int? = aggo.insertReturning(People, People.id) {
            People.email setTo Email("ret@ret"); People.fullName setTo "Ret"; People.active setTo true
        }
        newId shouldNotBe null
        (newId!! > 0) shouldBe true

        aggo.delete(People) { where { People.id eq newId } }
    }

    "ValueClassCodec round-trips through bind + decode (C-13)".config(enabledIf = { dockerAvailable }) {
        aggo.insert(People) {
            People.email setTo Email("vc@vc"); People.fullName setTo "VC"; People.active setTo true
        }

        val list = aggo.fetchAll(People) { where { People.email eq Email("vc@vc") } }
        list.size shouldBe 1
        list[0].email shouldBe Email("vc@vc")

        aggo.delete(People) { where { People.email eq Email("vc@vc") } }
    }

    "paginate returns entities count and total pages".config(enabledIf = { dockerAvailable }) {
        aggo.tx {
            repeat(5) { i ->
                insert(People) {
                    People.email setTo Email("page$i@page")
                    People.fullName setTo "Page $i"
                    People.active setTo true
                }
            }
        }

        val (entities, count, totalPages) = aggo.paginate(People, page = 2, size = 2) {
            where { People.active eq true }
            orderBy { People.id.asc() }
        }

        entities.map { it.name } shouldBe listOf("Page 2", "Page 3")
        count shouldBe 5L
        totalPages shouldBe 3

        aggo.delete(People)
    }

    "fetchAll and paginate typed-result overloads return Query values".config(enabledIf = { dockerAvailable }) {
        aggo.tx {
            repeat(3) { i ->
                insert(People) {
                    People.email setTo Email("typed$i@typed")
                    People.fullName setTo "Typed $i"
                    People.active setTo true
                }
            }
        }

        val all: Query<List<Person>, *> = aggo.fetchAll(People, errorMap = com.aggitech.aggo.runtime.ConstraintErrorMap.empty) {
            where { People.active eq true }
            orderBy { People.id.asc() }
        }
        (all as Query.Success).value.map { it.name } shouldBe listOf("Typed 0", "Typed 1", "Typed 2")

        val page: Query<Triple<List<Person>, Long, Int>, *> =
            aggo.paginate(People, page = 1, size = 2, errorMap = com.aggitech.aggo.runtime.ConstraintErrorMap.empty) {
                where { People.active eq true }
                orderBy { People.id.asc() }
            }
        (page as Query.Success).value.let { (entities, count, totalPages) ->
            entities.map { it.name } shouldBe listOf("Typed 0", "Typed 1")
            count shouldBe 3L
            totalPages shouldBe 2
        }

        aggo.delete(People)
    }

    "update returns affected rows".config(enabledIf = { dockerAvailable }) {
        aggo.insert(People) {
            People.email setTo Email("u@u"); People.fullName setTo "U"; People.active setTo true
        }

        val affected = aggo.update(People) {
            People.active setTo false
            where { People.email eq Email("u@u") }
        }
        affected shouldBe 1L

        aggo.delete(People) { where { People.email eq Email("u@u") } }
    }

    "one-shot update and tx { update } produce the same row state".config(enabledIf = { dockerAvailable }) {
        aggo.insert(People) {
            People.email setTo Email("eq@eq"); People.fullName setTo "Eq"; People.active setTo true
        }

        val viaShortcut = aggo.update(People) {
            People.active setTo false
            where { People.email eq Email("eq@eq") }
        }

        val viaTx = aggo.tx {
            update(People) {
                People.active setTo true
                where { People.email eq Email("eq@eq") }
            }
        }

        viaShortcut shouldBe 1L
        viaTx shouldBe 1L

        val row = aggo.fetchOne(People) { where { People.email eq Email("eq@eq") } }!!
        row.active shouldBe true

        aggo.delete(People) { where { People.email eq Email("eq@eq") } }
    }

    "applyMigration is idempotent — second call returns skipped=true".config(enabledIf = { dockerAvailable }) {
        val schema = migrationSchema("2026.05.25.idempotency", listOf(MigrationSmokeTable), PostgresDialect)
        val plan = migrationPlan(schema, PostgresDialect, ifNotExists = true)

        val first = aggo.applyMigration(plan)
        first.skipped shouldBe false
        first.statementsExecuted shouldBe 1

        val second = aggo.applyMigration(plan)
        second.skipped shouldBe true
        second.statementsExecuted shouldBe 0
    }

    "applyMigration rejects migration when fromVersion prerequisite is not applied".config(enabledIf = { dockerAvailable }) {
        val previous = migrationSchema("2026.05.25.never-applied", listOf(MigrationSmokeTable), PostgresDialect)
        val current = migrationSchema("2026.05.25.depends-on-missing", listOf(MigrationSmokeTable), PostgresDialect)
        val plan = migrationPlan(current, PostgresDialect, previous = previous, ifNotExists = true)

        shouldThrow<IllegalArgumentException> {
            aggo.applyMigration(plan)
        }.message!!.let { msg ->
            msg shouldBe "migration 2026.05.25.depends-on-missing requires '2026.05.25.never-applied' to be applied first"
        }
    }

    "applyMigrations applies all files and skips applied on re-run".config(enabledIf = { dockerAvailable }) {
        val dir = Files.createTempDirectory("aggo-it-migrations")
        val sql = "SELECT 1;"
        val at = Instant.now()
        val entries = listOf(
            MigrationFileEntry("2026.05.25.it-file-001", null, at, computeChecksum(sql, at), sql),
            MigrationFileEntry("2026.05.25.it-file-002", "2026.05.25.it-file-001", at, computeChecksum(sql, at), sql),
        )

        val results = aggo.tx { applyMigrations(entries) }
        results.map { it.skipped } shouldBe listOf(false, false)
        results.map { it.statementsExecuted } shouldBe listOf(1, 1)

        val results2 = aggo.tx { applyMigrations(entries) }
        results2.map { it.skipped } shouldBe listOf(true, true)
    }

    "applyMigrations throws on checksum mismatch".config(enabledIf = { dockerAvailable }) {
        val at = Instant.now()
        val realSql = "SELECT 1;"
        val tamperedSql = "SELECT 2;"
        val entry = MigrationFileEntry(
            version = "2026.05.25.it-tampered",
            fromVersion = null,
            generatedAt = at,
            checksum = computeChecksum(realSql, at),
            sql = tamperedSql,
        )
        shouldThrow<IllegalStateException> {
            aggo.tx { applyMigrations(listOf(entry)) }
        }.message!!.let { msg ->
            msg shouldBe "Checksum mismatch for migration 2026.05.25.it-tampered: stored=${computeChecksum(realSql, at)} computed=${computeChecksum(tamperedSql, at)}"
        }
    }

    "pool handles many concurrent coroutines without exhausting".config(enabledIf = { dockerAvailable }) {
        // Seed
        aggo.tx {
            repeat(20) { i ->
                insert(People) {
                    People.email setTo Email("p$i@p"); People.fullName setTo "P$i"; People.active setTo true
                }
            }
        }

        val concurrent = 50
        val sizes = coroutineScope {
            (1..concurrent).map {
                async { aggo.fetchAll(People).size }
            }.awaitAll()
        }
        sizes.distinct() shouldBe listOf(20)

        aggo.delete(People)
    }
})
