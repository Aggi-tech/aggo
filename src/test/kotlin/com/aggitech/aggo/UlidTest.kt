package com.aggitech.aggo

import com.aggitech.aggo.schema.ids.Tsid
import com.aggitech.aggo.schema.ids.Ulid
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

class UlidTest : FeatureSpec({

    feature("ULID generation") {
        scenario("generated identifiers use the canonical 26-character Crockford alphabet") {
            repeat(1_000) {
                val id = Ulid.generate()
                id.value.length shouldBe 26
                id.value.matches(Regex("^[0-9A-HJKMNP-TV-Z]{26}$")) shouldBe true
            }
        }

        scenario("a generated batch is strictly increasing and lexicographically sortable") {
            val ids = (1..10_000).map { Ulid.generate() }

            ids.toSet() shouldHaveSize ids.size
            ids.sorted() shouldBe ids
            for (i in 1 until ids.size) {
                ids[i] shouldBeGreaterThan ids[i - 1]
            }
        }

        scenario("concurrent generation produces no duplicate identifiers") {
            val count = 5_000
            val ids: List<Ulid> = runBlocking {
                coroutineScope {
                    (1..count).map { async { Ulid.generate() } }.awaitAll()
                }
            }
            ids.toSet() shouldHaveSize count
        }
    }

    feature("ULID parsing") {
        scenario("canonical and lowercase values normalize to the same identifier") {
            val id = Ulid.generate()

            Ulid.parse(id.value) shouldBe id
            Ulid.parse(id.value.lowercase()).value shouldBe id.value
        }

        scenario("invalid alphabet characters and invalid length are rejected") {
            listOf('I', 'L', 'O', 'U').forEach { invalid ->
                shouldThrow<IllegalArgumentException> { Ulid.parse(invalid.toString().repeat(26)) }
            }
            shouldThrow<IllegalArgumentException> { Ulid.parse("ABC123") }
        }
    }

    feature("TSID generation and parsing") {
        scenario("generated identifiers are canonical, parseable, unique, and strictly monotonic") {
            val ids = (1..5_000).map { Tsid.generate() }

            ids.first().value.length shouldBe 13
            Tsid.parse(ids.first().value) shouldBe ids.first()
            ids.toSet() shouldHaveSize ids.size
            for (i in 1 until ids.size) {
                ids[i] shouldBeGreaterThan ids[i - 1]
            }
        }
    }
})
