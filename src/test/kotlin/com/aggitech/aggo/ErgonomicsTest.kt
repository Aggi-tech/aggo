package com.aggitech.aggo

import com.aggitech.aggo.schema.BooleanCodec
import com.aggitech.aggo.schema.IntCodec
import com.aggitech.aggo.schema.StringCodec
import com.aggitech.aggo.schema.Table
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

class ErgonomicsTest : StringSpec({

    // E-2.1: required() returns the decoded value on a non-null column
    "required(row) returns decoded value" {
        val row = MapRow(mapOf("id" to 42, "label" to "Sprocket", "note" to null))
        WidgetsTable.id.required(row) shouldBe 42
        WidgetsTable.label.required(row) shouldBe "Sprocket"
    }

    // E-2.2: required() throws when the column is null — same contract as readRequired()
    "required(row) throws when column value is null" {
        val row = MapRow(mapOf("id" to null, "label" to "x", "note" to null))
        shouldThrow<IllegalStateException> {
            WidgetsTable.id.required(row)
        }
    }

    // E-2.3: nullable() returns null without throwing
    "nullable(row) returns null for absent column value" {
        val row = MapRow(mapOf("id" to 1, "label" to "x", "note" to null))
        WidgetsTable.note.nullable(row) shouldBe null
    }

    // E-2.4: nullable() returns the value when present
    "nullable(row) returns decoded value when column is non-null" {
        val row = MapRow(mapOf("id" to 1, "label" to "x", "note" to "extra info"))
        WidgetsTable.note.nullable(row) shouldBe "extra info"
    }

    // E-2.5: required() and readRequired() are equivalent — required() is purely an alias
    "required(row) and readRequired(row) produce identical results" {
        val row = MapRow(mapOf("id" to 7, "label" to "Bolt", "note" to null))
        WidgetsTable.id.required(row) shouldBe WidgetsTable.id.readRequired(row)
        WidgetsTable.label.required(row) shouldBe WidgetsTable.label.readRequired(row)
    }

    // E-2.6: nullable() and read() are equivalent
    "nullable(row) and read(row) produce identical results" {
        val row = MapRow(mapOf("id" to 1, "label" to "x", "note" to "hi"))
        WidgetsTable.note.nullable(row) shouldBe WidgetsTable.note.read(row)
    }

    // E-2.7: function reference col::required compiles to (Row) -> V and can be used as a mapper
    "col::required can be used as a (Row) -> V function reference" {
        val readId: (Row) -> Int = WidgetsTable.id::required
        val row = MapRow(mapOf("id" to 99, "label" to "Nut", "note" to null))
        readId(row) shouldBe 99
    }

    // E-2.8: function reference col::nullable compiles to (Row) -> V? and returns null
    "col::nullable can be used as a (Row) -> V? function reference" {
        val readNote: (Row) -> String? = WidgetsTable.note::nullable
        val row = MapRow(mapOf("id" to 1, "label" to "x", "note" to null))
        readNote(row) shouldBe null
    }

    // E-2.9: fromRow built with required/nullable produces a correct entity
    "fromRow built with required/nullable reads a full entity" {
        val row = MapRow(mapOf("id" to 5, "label" to "Gear", "note" to "smooth"))
        val widget = WidgetsTable.fromRow(row)
        widget shouldBe Widget(5, "Gear", "smooth")
    }

    // E-2.10: fromRow with nullable column absent returns null in entity
    "fromRow with nullable column missing returns null field" {
        val row = MapRow(mapOf("id" to 3, "label" to "Pin", "note" to null))
        val widget = WidgetsTable.fromRow(row)
        widget.note shouldBe null
        widget.id shouldNotBe null
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
