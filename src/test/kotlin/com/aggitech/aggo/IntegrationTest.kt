package com.aggitech.aggo

import com.aggitech.aggo.dsl.delete
import com.aggitech.aggo.dsl.eq
import com.aggitech.aggo.dsl.insert
import com.aggitech.aggo.dsl.leftJoin
import com.aggitech.aggo.dsl.orderBy
import com.aggitech.aggo.dsl.select
import com.aggitech.aggo.dsl.update
import com.aggitech.aggo.dsl.where
import com.aggitech.aggo.runtime.Aggo
import com.aggitech.aggo.runtime.AggoPool
import com.aggitech.aggo.runtime.PoolConfig
import com.aggitech.aggo.runtime.PostgresConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant

/**
 * Verifica empiricamente em Postgres real (Testcontainers) os bugs do AggORM original
 * que motivaram esta lib:
 *   - C-1/C-2: transações reais (rollback funciona)
 *   - C-3: SELECT com >1 linha não trava em awaitSingle
 *   - C-4: placeholder Postgres ($N) funciona
 *   - C-5/C-6: insertReturning devolve PK tipada
 *   - C-13: bind de @JvmInline value class
 *
 * Pulado automaticamente se Docker não estiver disponível.
 */
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
            aggo.read { session ->
                session.executeRaw(
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
                session.executeRaw(
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
        aggo.tx { session ->
            session.insert(insert(People) {
                People.email setTo Email("a@a"); People.fullName setTo "A"; People.active setTo true
            })
            session.insert(insert(People) {
                People.email setTo Email("b@b"); People.fullName setTo "B"; People.active setTo true
            })
            session.insert(insert(People) {
                People.email setTo Email("c@c"); People.fullName setTo "C"; People.active setTo false
            })
        }

        val all = aggo.read { it.fetchAll(select(People)) }
        all.size shouldBe 3
        all.map { it.name } shouldBe listOf("A", "B", "C")

        aggo.read { it.delete(delete(People)) }
    }

    "LEFT JOIN maps nested objects and keeps right null when there is no match".config(enabledIf = { dockerAvailable }) {
        val annaId = aggo.tx { session ->
            session.insertReturning(
                insert(People) {
                    People.email setTo Email("anna@pets")
                    People.fullName setTo "Anna"
                    People.active setTo true
                },
                People.id,
            )!!
        }
        val bobId = aggo.tx { session ->
            session.insertReturning(
                insert(People) {
                    People.email setTo Email("bob@pets")
                    People.fullName setTo "Bob"
                    People.active setTo true
                },
                People.id,
            )!!
        }

        aggo.tx { session ->
            session.insert(insert(Pets) {
                Pets.ownerId setTo annaId
                Pets.petName setTo "Nina"
            })
        }

        val rows = aggo.read { session ->
            session.fetchAllJoined(
                People.leftJoin(Pets) { People.id eq Pets.ownerId }
                    .where { People.active eq true }
                    .orderBy { People.id.asc() }
            )
        }

        rows.map { it.left.name } shouldBe listOf("Anna", "Bob")
        rows[0].right?.name shouldBe "Nina"
        rows[1].right shouldBe null

        aggo.read { it.delete(delete(Pets)) }
        aggo.read { it.delete(delete(People) { where { People.id eq annaId } }) }
        aggo.read { it.delete(delete(People) { where { People.id eq bobId } }) }
    }

    "tx { ... } rolls back on exception (C-1, C-2)".config(enabledIf = { dockerAvailable }) {
        val countBefore = aggo.read { it.fetchAll(select(People)).size }

        shouldThrow<IllegalStateException> {
            aggo.tx { session ->
                session.insert(insert(People) {
                    People.email setTo Email("x@x"); People.fullName setTo "X"; People.active setTo true
                })
                session.insert(insert(People) {
                    People.email setTo Email("y@y"); People.fullName setTo "Y"; People.active setTo true
                })
                error("boom — should rollback both inserts")
            }
        }

        val countAfter = aggo.read { it.fetchAll(select(People)).size }
        countAfter shouldBe countBefore // both inserts rolled back
    }

    "insertReturning decodes PK with the column codec (C-5, C-6)".config(enabledIf = { dockerAvailable }) {
        val newId: Int? = aggo.tx { session ->
            session.insertReturning(
                insert(People) {
                    People.email setTo Email("ret@ret"); People.fullName setTo "Ret"; People.active setTo true
                },
                People.id,
            )
        }
        newId shouldNotBe null
        (newId!! > 0) shouldBe true

        aggo.read { it.delete(delete(People) { where { People.id eq newId } }) }
    }

    "ValueClassCodec round-trips through bind + decode (C-13)".config(enabledIf = { dockerAvailable }) {
        aggo.tx { session ->
            session.insert(insert(People) {
                People.email setTo Email("vc@vc"); People.fullName setTo "VC"; People.active setTo true
            })
        }

        val list = aggo.read {
            it.fetchAll(select(People) { where { People.email eq Email("vc@vc") } })
        }
        list.size shouldBe 1
        list[0].email shouldBe Email("vc@vc")

        aggo.read { it.delete(delete(People) { where { People.email eq Email("vc@vc") } }) }
    }

    "update returns affected rows".config(enabledIf = { dockerAvailable }) {
        aggo.tx { session ->
            session.insert(insert(People) {
                People.email setTo Email("u@u"); People.fullName setTo "U"; People.active setTo true
            })
        }

        val affected = aggo.read {
            it.update(update(People) {
                People.active setTo false
                where { People.email eq Email("u@u") }
            })
        }
        affected shouldBe 1L

        aggo.read { it.delete(delete(People) { where { People.email eq Email("u@u") } }) }
    }

    "pool handles many concurrent coroutines without exhausting".config(enabledIf = { dockerAvailable }) {
        // Seed
        aggo.tx { session ->
            repeat(20) { i ->
                session.insert(insert(People) {
                    People.email setTo Email("p$i@p"); People.fullName setTo "P$i"; People.active setTo true
                })
            }
        }

        val concurrent = 50
        val sizes = coroutineScope {
            (1..concurrent).map {
                async { aggo.read { it.fetchAll(select(People)).size } }
            }.awaitAll()
        }
        sizes.distinct() shouldBe listOf(20)

        aggo.read { it.delete(delete(People)) }
    }
})
