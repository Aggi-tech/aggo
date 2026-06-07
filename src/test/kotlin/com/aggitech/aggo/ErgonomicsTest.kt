package com.aggitech.aggo

import com.aggitech.aggo.schema.BooleanCodec
import com.aggitech.aggo.schema.IntCodec
import com.aggitech.aggo.schema.StringCodec
import com.aggitech.aggo.schema.Table
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata

// E-2: fixture with a nullable column to test both required() and nullable()
private data class Widget(val id: Int, val label: String, val note: String?)

private object WidgetsTable : Table<Widget>("widgets") {
    val id    = column("id",    IntCodec,    isPrimaryKey = true) { it.id }
    val label = column("label", StringCodec)                     { it.label }
    val note  = column("note",  StringCodec, isNullable = true)  { it.note }

    override fun fromRow(row: Row): Widget = Widget(
        id    = id.required(row),
        label = label.required(row),
        note  = note.nullable(row),
    )
}

class ErgonomicsTest : BehaviorSpec({

    given("a required column") {
        `when`("the database row contains a value") {
            then("required decodes the value using the column codec") {
                val row = MapRow(mapOf("id" to 42, "label" to "Sprocket", "note" to null))

                WidgetsTable.id.required(row) shouldBe 42
                WidgetsTable.label.required(row) shouldBe "Sprocket"
            }
        }

        `when`("the database row contains null") {
            then("required fails instead of constructing an invalid entity") {
                val row = MapRow(mapOf("id" to null, "label" to "x", "note" to null))

                shouldThrow<IllegalStateException> {
                    WidgetsTable.id.required(row)
                }
            }
        }
    }

    given("a nullable column") {
        `when`("the database row contains null or a value") {
            then("nullable preserves the database nullability contract") {
                val absent = MapRow(mapOf("id" to 1, "label" to "x", "note" to null))
                val present = MapRow(mapOf("id" to 1, "label" to "x", "note" to "extra info"))

                WidgetsTable.note.nullable(absent) shouldBe null
                WidgetsTable.note.nullable(present) shouldBe "extra info"
            }
        }
    }

    given("required and nullable column readers") {
        `when`("they are used as function references") {
            then("they remain valid explicit row mappers") {
                val readId: (Row) -> Int = WidgetsTable.id::required
                val readNote: (Row) -> String? = WidgetsTable.note::nullable
                val row = MapRow(mapOf("id" to 99, "label" to "Nut", "note" to null))

                readId(row) shouldBe 99
                readNote(row) shouldBe null
            }
        }
    }

    given("a table fromRow mapper built with required and nullable readers") {
        `when`("a complete database row is mapped") {
            then("the expected domain entity is constructed") {
                val row = MapRow(mapOf("id" to 5, "label" to "Gear", "note" to "smooth"))

                WidgetsTable.fromRow(row) shouldBe Widget(5, "Gear", "smooth")
            }
        }
    }
})

private class MapRow(private val values: Map<String, Any?>) : Row {
    override fun getMetadata(): RowMetadata = error("not used in this test")

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> get(name: String, type: Class<T>): T? = values[name] as T?

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> get(index: Int, type: Class<T>): T? =
        error("positional reads are not used in this test")
}
