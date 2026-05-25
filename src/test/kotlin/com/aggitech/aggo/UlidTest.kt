package com.aggitech.aggo

import com.aggitech.aggo.schema.ids.Tsid
import com.aggitech.aggo.schema.ids.Ulid
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

class UlidTest : StringSpec({

    "ULID is always 26 chars Crockford base32" {
        repeat(1_000) {
            val id = Ulid.generate()
            id.value.length shouldBe 26
            id.value.matches(Regex("^[0-9A-HJKMNP-TV-Z]{26}$")) shouldBe true
        }
    }

    "ULIDs generated in the same ms are strictly increasing (monotonic spec)" {
        val ids = (1..10_000).map { Ulid.generate() }
        for (i in 1 until ids.size) {
            (ids[i] > ids[i - 1] || ids[i] == ids[i - 1]) shouldBe true
        }
        // Strict monotonicity: consecutive duplicates would violate ULID spec.
        ids.toSet() shouldHaveSize ids.size
    }

    "ULID is sortable: a generated batch is the same as its sorted permutation" {
        val ids = (1..1_000).map { Ulid.generate() }
        ids.sorted() shouldBe ids
    }

    "Ulid.parse round-trips the canonical value" {
        val id = Ulid.generate()
        Ulid.parse(id.value) shouldBe id
        // accepts lower-case too
        Ulid.parse(id.value.lowercase()).value shouldBe id.value
    }

    "Ulid.parse rejects invalid alphabet chars (I, L, O, U)" {
        shouldThrow<IllegalArgumentException> { Ulid.parse("I".repeat(26)) }
        shouldThrow<IllegalArgumentException> { Ulid.parse("L".repeat(26)) }
        shouldThrow<IllegalArgumentException> { Ulid.parse("O".repeat(26)) }
        shouldThrow<IllegalArgumentException> { Ulid.parse("U".repeat(26)) }
        // Wrong length
        shouldThrow<IllegalArgumentException> { Ulid.parse("ABC123") }
    }

    "ULID generation is thread-safe under high concurrency" {
        val n = 5_000
        val ids: List<Ulid> = runBlocking {
            coroutineScope {
                (1..n).map { async { Ulid.generate() } }.awaitAll()
            }
        }
        ids.toSet() shouldHaveSize n
    }

    "TSID is 13 chars and parse round-trips" {
        val t = Tsid.generate()
        t.value.length shouldBe 13
        Tsid.parse(t.value) shouldBe t
    }

    "TSIDs are strictly monotonic within a tight loop" {
        val ids = (1..5_000).map { Tsid.generate() }
        ids.toSet() shouldHaveSize ids.size
        for (i in 1 until ids.size) {
            ids[i] shouldBeGreaterThan ids[i - 1]
        }
    }
})
