package com.aggitech.aggo

import com.aggitech.aggo.schema.IntCodec
import com.aggitech.aggo.schema.StringCodec
import com.aggitech.aggo.schema.Table
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.r2dbc.spi.Row

/** V-2: schema-level identifier validation runs at object init, not at render. */
class SchemaValidationTest : StringSpec({

    "Table with a non-identifier name throws at construction (V-2)" {
        shouldThrow<IllegalArgumentException> {
            object : Table<Any>("bad; DROP TABLE x") {
                override fun fromRow(row: Row): Any = error("not used")
            }
        }
    }

    "Table.column with a non-identifier column name throws at construction (V-2)" {
        shouldThrow<IllegalArgumentException> {
            object : Table<Any>("good_table") {
                @Suppress("unused")
                val col = column("first name", StringCodec) { _: Any -> "x" }
                override fun fromRow(row: Row): Any = error("not used")
            }
        }
    }

    "Table.column with duplicate name throws at construction (V-2)" {
        shouldThrow<IllegalArgumentException> {
            object : Table<Any>("dup_table") {
                @Suppress("unused")
                val a = column("id", IntCodec) { _: Any -> 1 }
                @Suppress("unused")
                val b = column("id", IntCodec) { _: Any -> 2 }
                override fun fromRow(row: Row): Any = error("not used")
            }
        }
    }

    "Valid table and columns construct fine and report the right names" {
        val t = object : Table<Any>("legit_table") {
            val one = column("col_one", IntCodec) { _: Any -> 1 }
            override fun fromRow(row: Row): Any = error("not used")
        }
        t.name shouldBe "legit_table"
        t.columns.size shouldBe 1
        t.one.name shouldBe "col_one"
    }
})
