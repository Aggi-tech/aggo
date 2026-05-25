package com.aggitech.aggo

import com.aggitech.aggo.dsl.inList
import com.aggitech.aggo.query.Predicate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** V-8: IN list size cap. */
class PredicateLimitTest : StringSpec({

    "Predicate.In throws when values exceed MAX_IN_SIZE (V-8)" {
        val tooMany = (1..Predicate.In.MAX_IN_SIZE + 1).toList()
        shouldThrow<IllegalArgumentException> {
            People.id inList tooMany
        }
    }

    "Predicate.In at exactly MAX_IN_SIZE builds successfully (V-8)" {
        val justRight = (1..Predicate.In.MAX_IN_SIZE).toList()
        val pred = People.id inList justRight
        (pred is Predicate.In<*>) shouldBe true
    }
})
