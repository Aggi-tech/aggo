package com.aggitech.aggo

import com.aggitech.aggo.migration.MigrationCheck
import com.aggitech.aggo.migration.MigrationColumn
import com.aggitech.aggo.migration.MigrationCustomType
import com.aggitech.aggo.migration.MigrationSchema
import com.aggitech.aggo.migration.MigrationTable
import com.aggitech.aggo.migration.migrationSchemaFromJson
import com.aggitech.aggo.migration.readSnapshot
import com.aggitech.aggo.migration.toJson
import com.aggitech.aggo.migration.writeSnapshot
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files

class MigrationSnapshotIOTest : StringSpec({

    fun simpleSchema(version: String = "v1") = MigrationSchema(
        version = version,
        tables = listOf(
            MigrationTable(
                name = "users",
                columns = listOf(
                    MigrationColumn("id", "UUID", nullable = false, generated = false),
                    MigrationColumn("name", "TEXT", nullable = false, generated = false),
                    MigrationColumn("bio", "TEXT", nullable = true, generated = false),
                    MigrationColumn("seq", "INTEGER", nullable = false, generated = true),
                ),
                checks = listOf(MigrationCheck("chk_users_name", "trim(\"name\") <> ''")),
                primaryKey = listOf("id"),
            )
        ),
    )

    "round-trips a basic schema through toJson / migrationSchemaFromJson" {
        val original = simpleSchema("2026.05.25.001")
        val json = original.toJson()
        val restored = migrationSchemaFromJson(json)
        restored shouldBe original
    }

    "round-trips nullable and generated flags correctly" {
        val original = simpleSchema()
        val restored = migrationSchemaFromJson(original.toJson())
        val col = restored.tables.single().columns
        col.find { it.name == "bio" }!!.nullable shouldBe true
        col.find { it.name == "seq" }!!.generated shouldBe true
        col.find { it.name == "id" }!!.nullable shouldBe false
        col.find { it.name == "id" }!!.generated shouldBe false
    }

    "round-trips a schema with composite primary key" {
        val schema = MigrationSchema(
            version = "v2",
            tables = listOf(
                MigrationTable(
                    name = "order_items",
                    columns = listOf(
                        MigrationColumn("order_id", "BIGINT", nullable = false),
                        MigrationColumn("product_id", "BIGINT", nullable = false),
                    ),
                    primaryKey = listOf("order_id", "product_id"),
                )
            ),
        )
        migrationSchemaFromJson(schema.toJson()) shouldBe schema
    }

    "round-trips a schema with CHECK expression containing backslashes (POSIX regex)" {
        val emailExpr = "\"email\" ~* '^[^@\\\\s]+@[^@\\\\s]+\\\\.[^@\\\\s]+\$'"
        val schema = MigrationSchema(
            version = "v3",
            tables = listOf(
                MigrationTable(
                    name = "accounts",
                    columns = listOf(MigrationColumn("email", "TEXT", nullable = false)),
                    checks = listOf(MigrationCheck("chk_accounts_email", emailExpr)),
                )
            ),
        )
        val restored = migrationSchemaFromJson(schema.toJson())
        restored.tables.single().checks.single().expression shouldBe emailExpr
    }

    "round-trips a schema with double-quote characters in CHECK expression" {
        val expr = "char_length(\"name\") <= 100"
        val schema = MigrationSchema(
            version = "v4",
            tables = listOf(
                MigrationTable(
                    name = "things",
                    columns = listOf(MigrationColumn("name", "TEXT", nullable = false)),
                    checks = listOf(MigrationCheck("chk_things_name", expr)),
                )
            ),
        )
        migrationSchemaFromJson(schema.toJson()).tables.single().checks.single().expression shouldBe expr
    }

    "round-trips multiple tables" {
        val schema = MigrationSchema(
            version = "v5",
            tables = listOf(
                MigrationTable("a", listOf(MigrationColumn("id", "UUID", false))),
                MigrationTable("b", listOf(MigrationColumn("id", "UUID", false))),
                MigrationTable("c", listOf(MigrationColumn("id", "UUID", false))),
            ),
        )
        val restored = migrationSchemaFromJson(schema.toJson())
        restored.tables.map { it.name } shouldBe listOf("a", "b", "c")
    }

    "migrationSchemaFromJson throws IllegalArgumentException on invalid JSON" {
        shouldThrow<IllegalArgumentException> {
            migrationSchemaFromJson("{not valid json")
        }
    }

    "migrationSchemaFromJson throws on missing 'version' field" {
        shouldThrow<IllegalArgumentException> {
            migrationSchemaFromJson("""{"tables":[]}""")
        }
    }

    "readSnapshot returns null when file does not exist" {
        val tmp = Files.createTempDirectory("aggo-snap-test")
        val file = tmp.resolve("missing.json")
        readSnapshot(file) shouldBe null
    }

    "writeSnapshot + readSnapshot round-trip via temp file" {
        val original = simpleSchema("2026.05.25.write-test")
        val tmp = Files.createTempDirectory("aggo-snap-test")
        val file = tmp.resolve("snapshot.json")

        writeSnapshot(original, file)
        val restored = readSnapshot(file)

        restored shouldNotBe null
        restored shouldBe original
    }

    "writeSnapshot creates parent directories if absent" {
        val original = simpleSchema()
        val tmp = Files.createTempDirectory("aggo-snap-test")
        val nested = tmp.resolve("deep/nested/snapshot.json")
        writeSnapshot(original, nested)
        readSnapshot(nested) shouldBe original
    }

    // SN-1: customTypes round-trips through JSON serialization
    "round-trips a schema with customTypes" {
        val schema = MigrationSchema(
            version = "v-custom",
            tables = listOf(
                MigrationTable("items", listOf(MigrationColumn("id", "UUID", false)))
            ),
            customTypes = listOf(
                MigrationCustomType("status_type", "CREATE TYPE status_type AS ENUM ('A','B');"),
                MigrationCustomType("priority_level", "CREATE TYPE priority_level AS ENUM ('LOW','HIGH');"),
            ),
        )
        val restored = migrationSchemaFromJson(schema.toJson())
        restored.customTypes.size shouldBe 2
        restored.customTypes[0].name shouldBe "status_type"
        restored.customTypes[0].createDdl shouldBe "CREATE TYPE status_type AS ENUM ('A','B');"
        restored.customTypes[1].name shouldBe "priority_level"
    }

    // SN-2: JSON without customTypes field deserializes as empty list (backwards compatibility)
    "migrationSchemaFromJson tolerates missing customTypes field" {
        val json = """
            {
              "version": "v-old",
              "tables": [
                {
                  "name": "legacy",
                  "columns": [{"name":"id","sqlType":"TEXT","nullable":false,"generated":false}],
                  "checks": [],
                  "primaryKey": ["id"]
                }
              ]
            }
        """.trimIndent()
        val schema = migrationSchemaFromJson(json)
        schema.customTypes shouldBe emptyList()
    }

    // SN-3: empty customTypes serializes to empty array and round-trips cleanly
    "empty customTypes serializes to [] and round-trips" {
        val schema = simpleSchema("v-empty-ct")
        schema.customTypes shouldBe emptyList()
        val restored = migrationSchemaFromJson(schema.toJson())
        restored.customTypes shouldBe emptyList()
    }
})
