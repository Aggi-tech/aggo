package com.aggitech.aggo.runtime

import com.aggitech.aggo.Email
import com.aggitech.aggo.People
import com.aggitech.aggo.Pets
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import java.time.Instant

class PositionalRowTest : DescribeSpec({

    describe("a joined database row containing duplicate column names") {
        it("maps each table from its own positional offset") {
            val raw = ListRow(
                listOf(
                    7,
                    "owner@example.test",
                    "Owner",
                    true,
                    Instant.parse("2024-01-01T00:00:00Z"),
                    11,
                    7,
                    "Nina",
                )
            )

            val left = People.fromRow(
                PositionalRow(
                    raw,
                    mapOf("id" to 0, "email" to 1, "name" to 2, "active" to 3, "created_at" to 4),
                )
            )
            val right = Pets.fromRow(
                PositionalRow(
                    raw,
                    mapOf("id" to 5, "owner_id" to 6, "name" to 7),
                )
            )

            left.id shouldBe 7
            left.email shouldBe Email("owner@example.test")
            left.name shouldBe "Owner"
            right.id shouldBe 11
            right.ownerId shouldBe 7
            right.name shouldBe "Nina"
        }
    }
})

private class ListRow(private val values: List<Any?>) : Row {
    override fun getMetadata(): RowMetadata = error("metadata is not used by this test")

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> get(index: Int, type: Class<T>): T? =
        values[index] as T?

    override fun <T : Any?> get(name: String, type: Class<T>): T? =
        error("name reads must be handled by PositionalRow")
}
