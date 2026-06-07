package com.aggitech.aggo

import com.aggitech.aggo.dsl.eq
import com.aggitech.aggo.dsl.orderBy
import com.aggitech.aggo.dsl.where
import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.dialect.PostgresTriggerDialect
import com.aggitech.aggo.migration.migrationPlan
import com.aggitech.aggo.migration.migrationSchema
import com.aggitech.aggo.notify.AggoListener
import com.aggitech.aggo.notify.NotifyChannel
import com.aggitech.aggo.notify.StringNotifyCodec
import com.aggitech.aggo.notify.notify
import com.aggitech.aggo.runtime.Aggo
import com.aggitech.aggo.runtime.AggoPool
import com.aggitech.aggo.runtime.AggoUnsafe
import com.aggitech.aggo.runtime.PoolConfig
import com.aggitech.aggo.runtime.PostgresConfig
import com.aggitech.aggo.schema.InstantCodec
import com.aggitech.aggo.schema.IntCodec
import com.aggitech.aggo.schema.LongCodec
import com.aggitech.aggo.schema.NotifyTrigger
import com.aggitech.aggo.schema.StringCodec
import com.aggitech.aggo.schema.Table
import com.aggitech.aggo.schema.TriggerEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.matchers.shouldBe
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.Row
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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

private object ReactiveUsers : Table<Unit>("reactive_users") {
    val id = column("id", LongCodec, isPrimaryKey = true) { null }
    val email = column("email", StringCodec) { null }

    override fun fromRow(row: Row): Unit = Unit
}

private val ReactiveUserEvents = NotifyChannel("reactive_user_events", StringNotifyCodec)

private val ReactiveUsersTrigger = NotifyTrigger(
    name = "trg_reactive_user_events",
    table = ReactiveUsers,
    channel = ReactiveUserEvents,
    events = setOf(TriggerEvent.Insert, TriggerEvent.Update),
    payloadSql = "NEW.id",
)

@OptIn(AggoUnsafe::class)
class IntegrationTest : FeatureSpec({

    val dockerAvailable = DockerClientFactory.instance().isDockerAvailable

    lateinit var pg: PostgreSQLContainer<*>
    lateinit var aggo: Aggo
    lateinit var listener: AggoListener

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
                pg.config(
                    host = pg.host,
                    port = pg.firstMappedPort,
                    database = pg.databaseName,
                    user = pg.username,
                    password = pg.password,
                    pool = PoolConfig(initialSize = 1, maxSize = 4),
                )
            )
        )
        listener = AggoListener(pg.connectionFactory())

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
            val schema = migrationSchema(
                version = "2026.06.07.reactive",
                tables = listOf(ReactiveUsers),
                dialect = PostgresDialect,
                triggers = listOf(ReactiveUsersTrigger),
            )
            aggo.tx.applyMigration(
                migrationPlan(schema, PostgresDialect, triggerDialect = PostgresTriggerDialect),
            )
        }
    }

    afterSpec {
        if (!dockerAvailable) return@afterSpec
        aggo.close()
        pg.stop()
    }

    feature("session and transaction execution") {
        scenario("basic read/write flow executes successfully").config(enabledIf = { dockerAvailable }) {
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

        scenario("an exception rolls back the transaction").config(enabledIf = { dockerAvailable }) {
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
    }

    feature("migration application") {
        scenario("a migration records its schema version").config(enabledIf = { dockerAvailable }) {
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
    }

    feature("reactive PostgreSQL notifications") {
        scenario("N-ACC-1: a trigger notification is delivered only after commit")
            .config(enabledIf = { dockerAvailable }) {
                val notification = receiveAfter(listener, ReactiveUserEvents) {
                    aggo.tx { tx ->
                        tx.insert(ReactiveUsers) {
                            ReactiveUsers.id setTo 101L
                            ReactiveUsers.email setTo "committed@example.test"
                        }
                    }
                }

                notification.channel shouldBe ReactiveUserEvents.name
                notification.payload shouldBe "101"
            }

        scenario("N-ACC-2: rollback does not deliver a trigger notification")
            .config(enabledIf = { dockerAvailable }) {
                val received = coroutineScope {
                    val pending = async {
                        withTimeoutOrNull(800.milliseconds) {
                            listener.listen(ReactiveUserEvents).first()
                        }
                    }
                    delay(250.milliseconds)
                    shouldThrow<IllegalStateException> {
                        aggo.tx { tx ->
                            tx.insert(ReactiveUsers) {
                                ReactiveUsers.id setTo 102L
                                ReactiveUsers.email setTo "rolled-back@example.test"
                            }
                            error("rollback")
                        }
                    }
                    pending.await()
                }

                received shouldBe null
            }

        scenario("N-ACC-5: Session.notify delivers only after its transaction commits")
            .config(enabledIf = { dockerAvailable }) {
                val notification = receiveAfter(listener, ReactiveUserEvents) {
                    aggo.tx.unsafe { tx ->
                        tx.notify(ReactiveUserEvents, "manual")
                    }
                }

                notification.payload shouldBe "manual"
            }

        scenario("N-ACC-12: two cold-flow collectors receive the same notification independently")
            .config(enabledIf = { dockerAvailable }) {
                val notifications = coroutineScope {
                    val first = async { withTimeout(5.seconds) { listener.listen(ReactiveUserEvents).first() } }
                    val second = async { withTimeout(5.seconds) { listener.listen(ReactiveUserEvents).first() } }
                    delay(300.milliseconds)
                    aggo.tx.unsafe { tx -> tx.notify(ReactiveUserEvents, "fanout") }
                    listOf(first.await(), second.await())
                }

                notifications.map { it.payload } shouldBe listOf("fanout", "fanout")
            }

        scenario("N-ACC-13: a near-8 kB PostgreSQL payload is delivered without truncation")
            .config(enabledIf = { dockerAvailable }) {
                val payload = "x".repeat(7_999)
                val notification = receiveAfter(listener, ReactiveUserEvents) {
                    aggo.tx.unsafe { tx -> tx.notify(ReactiveUserEvents, payload) }
                }

                notification.payload shouldBe payload
            }
    }
})

private fun PostgreSQLContainer<*>.config(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String,
    pool: PoolConfig,
): PostgresConfig = PostgresConfig(host, port, database, user, password, pool = pool)

private fun PostgreSQLContainer<*>.connectionFactory(): ConnectionFactory =
    ConnectionFactories.get(
        ConnectionFactoryOptions.builder()
            .option(ConnectionFactoryOptions.DRIVER, "postgresql")
            .option(ConnectionFactoryOptions.HOST, host)
            .option(ConnectionFactoryOptions.PORT, firstMappedPort)
            .option(ConnectionFactoryOptions.DATABASE, databaseName)
            .option(ConnectionFactoryOptions.USER, username)
            .option(ConnectionFactoryOptions.PASSWORD, password)
            .build(),
    )

private suspend fun <P : Any> receiveAfter(
    listener: AggoListener,
    channel: NotifyChannel<P>,
    action: suspend () -> Unit,
) = coroutineScope {
    val pending = async { withTimeout(5.seconds) { listener.listen(channel).first() } }
    delay(300.milliseconds)
    action()
    pending.await()
}
