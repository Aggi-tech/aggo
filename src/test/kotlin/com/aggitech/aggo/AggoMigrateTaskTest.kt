package com.aggitech.aggo

import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.migration.AggoMigrateTask
import com.aggitech.aggo.migration.readMigrationFiles
import com.aggitech.aggo.migration.readSnapshot
import com.aggitech.aggo.schema.IntCodec
import com.aggitech.aggo.schema.StringCodec
import com.aggitech.aggo.schema.Table
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.r2dbc.spi.Row
import java.nio.file.Files

private object TaskTable : Table<Unit>("task_items") {
    val id    = column("id",    IntCodec,    isPrimaryKey = true) { null }
    val title = column("title", StringCodec)                     { null }
    override fun fromRow(row: Row): Unit = Unit
}

class AggoMigrateTaskTest : StringSpec({

    fun tempTask(): Pair<AggoMigrateTask, java.nio.file.Path> {
        val base = Files.createTempDirectory("aggo-task-test")
        val task = object : AggoMigrateTask() {
            override val tables  = listOf(TaskTable)
            override val dialect = PostgresDialect
            override val snapshotFile  = base.resolve("snapshot.json")
            override val migrationsDir = base.resolve("migrations")
        }
        return task to base
    }

    // E-1.1: runFromArgs with empty args generates migration with timestamp-only version
    "runFromArgs with no args runs migration without name label" {
        val (task, base) = tempTask()
        task.runFromArgs(emptyArray())

        val snapshot = readSnapshot(base.resolve("snapshot.json"))
        snapshot shouldNotBe null
        snapshot!!.tables.map { it.name } shouldBe listOf("task_items")

        val files = readMigrationFiles(base.resolve("migrations"))
        files.size shouldBe 1
        files.single().sql shouldContain "task_items"
        // version label derived from timestamp — no underscore suffix
        files.single().version.contains('_') shouldBe false
    }

    // E-1.2: runFromArgs reads the migration name from args[0]
    "runFromArgs uses first positional argument as migration name" {
        val (task, base) = tempTask()
        task.runFromArgs(arrayOf("initial_schema"))

        val files = readMigrationFiles(base.resolve("migrations"))
        files.single().version shouldContain "initial_schema"
    }

    // E-1.3: runFromArgs ignores blank first argument and falls through to aggo.name property
    "runFromArgs ignores blank args and falls back to aggo.name system property" {
        val (task, base) = tempTask()
        System.setProperty("aggo.name", "from_sysprop")
        try {
            task.runFromArgs(arrayOf("  "))  // blank — should be ignored
            val files = readMigrationFiles(base.resolve("migrations"))
            files.single().version shouldContain "from_sysprop"
        } finally {
            System.clearProperty("aggo.name")
        }
    }

    // E-1.4: runFromArgs prefers args[0] over aggo.name system property
    "runFromArgs prefers positional arg over aggo.name system property" {
        val (task, base) = tempTask()
        System.setProperty("aggo.name", "from_sysprop")
        try {
            task.runFromArgs(arrayOf("from_arg"))
            val files = readMigrationFiles(base.resolve("migrations"))
            files.single().version shouldContain "from_arg"
            files.single().version.contains("from_sysprop") shouldBe false
        } finally {
            System.clearProperty("aggo.name")
        }
    }

    // E-1.5: subsequent run with same tables prints no-change and creates no new file
    "runFromArgs is idempotent — second run with unchanged tables creates no new migration" {
        val (task, base) = tempTask()
        task.runFromArgs(emptyArray())
        task.runFromArgs(emptyArray())

        val files = readMigrationFiles(base.resolve("migrations"))
        files.size shouldBe 1
    }
})
