package com.aggitech.aggo

import com.aggitech.aggo.dsl.inList
import com.aggitech.aggo.query.Predicate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/** V-8: IN list size cap. */
class PredicateLimitTest : BehaviorSpec({

    given("the maximum supported IN-list size") {
        `when`("a query contains one value above the limit") {
            then("predicate construction fails before rendering") {
                val tooMany = (1..Predicate.In.MAX_IN_SIZE + 1).toList()

                shouldThrow<IllegalArgumentException> {
                    People.id inList tooMany
                }
            }
        }

        `when`("a query contains exactly the supported limit") {
            then("the complete value list is preserved in the predicate") {
                val justRight = (1..Predicate.In.MAX_IN_SIZE).toList()
                val predicate = People.id inList justRight

                (predicate as Predicate.In<*>).values shouldBe justRight
            }
        }
    }
})
