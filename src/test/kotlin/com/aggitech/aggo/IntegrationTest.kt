package com.aggitech.aggo

import com.aggitech.aggo.dsl.eq
import com.aggitech.aggo.dsl.orderBy
import com.aggitech.aggo.dsl.where
import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.migration.migrationPlan
import com.aggitech.aggo.migration.migrationSchema
import com.aggitech.aggo.runtime.Aggo
import com.aggitech.aggo.runtime.AggoPool
import com.aggitech.aggo.runtime.AggoUnsafe
import com.aggitech.aggo.runtime.PoolConfig
import com.aggitech.aggo.runtime.PostgresConfig
import com.aggitech.aggo.schema.InstantCodec
import com.aggitech.aggo.schema.IntCodec
import com.aggitech.aggo.schema.StringCodec
import com.aggitech.aggo.schema.Table
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.r2dbc.spi.Row
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

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
            aggo.tx.unsafe { raw ->
                raw.executeRaw(
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
            }
        }
    }

    afterSpec {
        if (!dockerAvailable) return@afterSpec
        aggo.close()
        pg.stop()
    }

    "session and tx builders execute basic read/write flow".config(enabledIf = { dockerAvailable }) {
        aggo.tx { tx ->
            tx.insert(People) {
                People.email setTo Email("a@a")
                People.fullName setTo "A"
                People.active setTo true
            }
            tx.insert(People) {
                People.email setTo Email("b@b")
                People.fullName setTo "B"
                People.active setTo true
            }
        }

        val all = aggo.session.fetchAll(People) {
            orderBy { People.id.asc() }
        }
        all.map { it.name } shouldBe listOf("A", "B")

        aggo.tx.delete(People)
    }

    "tx builder rolls back on exception".config(enabledIf = { dockerAvailable }) {
        val countBefore = aggo.session.fetchAll(People).size

        shouldThrow<IllegalStateException> {
            aggo.tx { tx ->
                tx.insert(People) {
                    People.email setTo Email("x@x")
                    People.fullName setTo "X"
                    People.active setTo true
                }
                error("boom")
            }
        }

        aggo.session.fetchAll(People).size shouldBe countBefore
    }

    "tx migration application records schema version".config(enabledIf = { dockerAvailable }) {
        val schema = migrationSchema("2026.05.25.integration", listOf(MigrationSmokeTable), PostgresDialect)
        val plan = migrationPlan(schema, PostgresDialect)

        val result = aggo.tx.applyMigration(plan)
        result.fromVersion shouldBe null
        result.toVersion shouldBe schema.version
        result.statementsExecuted shouldBe 1

        val applied = aggo.session.fetchOne(AggoSchemaVersions) {
            where { AggoSchemaVersions.version eq schema.version }
        }
        applied?.description shouldBe "create table migration_smoke"
    }
})
