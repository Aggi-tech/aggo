package com.aggitech.aggo

import com.aggitech.aggo.schema.IntCodec
import com.aggitech.aggo.schema.StringCodec
import com.aggitech.aggo.schema.Table
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.r2dbc.spi.Row

/** V-2: schema-level identifier validation runs at object init, not at render. */
class SchemaValidationTest : BehaviorSpec({

    given("schema metadata is created") {
        `when`("a table name contains SQL injection syntax") {
            then("construction fails before rendering") {
                shouldThrow<IllegalArgumentException> {
                    object : Table<Any>("bad; DROP TABLE x") {
                        override fun fromRow(row: Row): Any = error("not used")
                    }
                }
            }
        }

        `when`("a column name contains whitespace") {
            then("construction fails before rendering") {
                shouldThrow<IllegalArgumentException> {
                    object : Table<Any>("good_table") {
                        @Suppress("unused")
                        val col = column("first name", StringCodec) { _: Any -> "x" }
                        override fun fromRow(row: Row): Any = error("not used")
                    }
                }
            }
        }

        `when`("two columns use the same name") {
            then("construction fails before ambiguous metadata is exposed") {
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
        }

        `when`("table and column names are valid and unique") {
            then("the declared metadata is exposed unchanged") {
                val table = object : Table<Any>("legit_table") {
                    val one = column("col_one", IntCodec) { _: Any -> 1 }
                    override fun fromRow(row: Row): Any = error("not used")
                }

                table.name shouldBe "legit_table"
                table.columns.map { it.name } shouldBe listOf("col_one")
            }
        }
    }
})
