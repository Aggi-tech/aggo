package com.aggitech.aggo.dialect

import com.aggitech.aggo.schema.NotifyTrigger
import com.aggitech.aggo.schema.TriggerEvent

/**
 * Per-database DDL generation for [NotifyTrigger]s.
 *
 * Implemented alongside [MigrationDialect] — [com.aggitech.aggo.migration.MigrationGenerator]
 * accepts an optional list of triggers and includes their DDL in the [com.aggitech.aggo.migration.MigrationPlan]
 * via this interface. PostgreSQL emits a trigger function calling `pg_notify` directly;
 * MySQL and Oracle have no native pub/sub, so their triggers `INSERT` into a managed
 * outbox table (`aggo_notifications`) that [com.aggitech.aggo.notify.OutboxListener] polls.
 *
 * All identifiers are quoted via [SqlDialect.quoteIdentifier] (which validates through
 * [requireValidIdentifier]) — no string is interpolated without passing that gate.
 */
interface TriggerDialect {

    /**
     * Renders the complete DDL for a notification trigger, in execution order.
     * PostgreSQL: `CREATE FUNCTION` + `CREATE TRIGGER`. MySQL/Oracle: `CREATE TRIGGER`
     * inserting into the outbox table (one statement per event for MySQL, which
     * does not support multi-event triggers).
     *
     * [outboxTableName] names the managed outbox table MySQL/Oracle triggers
     * `INSERT` into — ignored by [PostgresTriggerDialect], which never needs one.
     * Pass the same name to [renderOutboxTableDdl] and to
     * [com.aggitech.aggo.notify.OutboxListener] so DDL and polling agree on it.
     */
    fun renderNotifyTriggerDdl(trigger: NotifyTrigger<*>, outboxTableName: String = OUTBOX_TABLE): List<String>

    /**
     * Renders DDL for the outbox table used by dialects without native pub/sub,
     * named [outboxTableName] (defaults to [OUTBOX_TABLE]). PostgreSQL returns an
     * empty list — it never needs one.
     */
    fun renderOutboxTableDdl(outboxTableName: String = OUTBOX_TABLE): List<String>

    /** Renders `DROP TRIGGER` (and any companion function) statements for [trigger]. */
    fun renderDropTriggerDdl(trigger: NotifyTrigger<*>): List<String>
}

/**
 * PostgreSQL: a `plpgsql` function that calls `pg_notify(channel, payload)`,
 * wired to the table via `CREATE TRIGGER ... EXECUTE FUNCTION`.
 *
 * `pg_notify` inside an `AFTER` trigger only fires on `COMMIT` — rollbacks never
 * reach listeners. That correctness property is native PostgreSQL behaviour, not
 * something Aggo implements.
 */
object PostgresTriggerDialect : TriggerDialect {

    override fun renderNotifyTriggerDdl(trigger: NotifyTrigger<*>, outboxTableName: String): List<String> {
        val dialect = PostgresDialect
        val fnName = functionName(trigger)
        val table = dialect.qualifyTableName(trigger.table.name)
        val triggerName = dialect.quoteIdentifier(trigger.name)
        val timing = trigger.timing.name.uppercase()
        val events = trigger.events.joinToString(" OR ") { it.name.uppercase() }
        val channel = dialect.requireValidChannelName(trigger.channel.name)

        // DELETE triggers never populate NEW and INSERT/UPDATE triggers never populate
        // OLD — a multi-event trigger must pick its row image at runtime via TG_OP,
        // not bake in whichever reference the author happened to write.
        val payloadExpr = "CASE WHEN TG_OP = 'DELETE' " +
            "THEN (${rewriteRowImage(trigger.payloadSql, useOld = true)}) " +
            "ELSE (${rewriteRowImage(trigger.payloadSql, useOld = false)}) END"

        val function = """
            CREATE OR REPLACE FUNCTION $fnName() RETURNS trigger
            LANGUAGE plpgsql AS $$
            BEGIN
                PERFORM pg_notify('$channel', ($payloadExpr)::text);
                RETURN COALESCE(NEW, OLD);
            END;
            $$;
        """.trimIndent()

        val dropTrigger = "DROP TRIGGER IF EXISTS $triggerName ON $table;"

        val createTrigger = """
            CREATE TRIGGER $triggerName
            $timing $events ON $table
            FOR EACH ROW EXECUTE FUNCTION $fnName();
        """.trimIndent()

        // One statement per entry — MigrationGenerator turns each into its own
        // MigrationStep, matching the one-statement-per-step convention used
        // for tables, checks, FKs, and indexes.
        return listOf(function, dropTrigger, createTrigger)
    }

    override fun renderOutboxTableDdl(outboxTableName: String): List<String> = emptyList()

    override fun renderDropTriggerDdl(trigger: NotifyTrigger<*>): List<String> {
        val dialect = PostgresDialect
        return listOf(
            "DROP TRIGGER IF EXISTS ${dialect.quoteIdentifier(trigger.name)} " +
                "ON ${dialect.qualifyTableName(trigger.table.name)};",
            "DROP FUNCTION IF EXISTS ${functionName(trigger)}();",
        )
    }

    private fun functionName(trigger: NotifyTrigger<*>): String =
        PostgresDialect.quoteIdentifier("aggo_notify_${trigger.name}")
}

/**
 * MySQL has no native pub/sub and cannot bind multiple events to one `CREATE TRIGGER`,
 * so each [TriggerEvent] gets its own trigger that inserts a row into the managed
 * `aggo_notifications` outbox table. [com.aggitech.aggo.notify.OutboxListener] polls
 * that table and replays rows as [com.aggitech.aggo.notify.Notification]s.
 */
object MySqlTriggerDialect : TriggerDialect {

    override fun renderNotifyTriggerDdl(trigger: NotifyTrigger<*>, outboxTableName: String): List<String> {
        val dialect = MySqlDialect
        val table = dialect.qualifyTableName(trigger.table.name)
        val timing = trigger.timing.name.uppercase()
        val outbox = dialect.quoteIdentifier(outboxTableName)
        val channel = dialect.requireValidChannelName(trigger.channel.name)

        return trigger.events.flatMap { event ->
            val name = dialect.quoteIdentifier(eventTriggerName(trigger, event))
            val payload = rewriteRowImage(trigger.payloadSql, useOld = event == TriggerEvent.Delete)
            listOf(
                "DROP TRIGGER IF EXISTS $name;",
                """
                CREATE TRIGGER $name
                $timing ${event.name.uppercase()} ON $table
                FOR EACH ROW
                INSERT INTO $outbox (${dialect.quoteIdentifier("channel")}, ${dialect.quoteIdentifier("payload")}, ${dialect.quoteIdentifier("created_at")})
                VALUES ('$channel', ($payload), NOW(3));
                """.trimIndent(),
            )
        }
    }

    override fun renderOutboxTableDdl(outboxTableName: String): List<String> {
        val dialect = MySqlDialect
        val table = dialect.quoteIdentifier(outboxTableName)
        return listOf(
            """
            CREATE TABLE IF NOT EXISTS $table (
                ${dialect.quoteIdentifier("id")} BIGINT AUTO_INCREMENT PRIMARY KEY,
                ${dialect.quoteIdentifier("channel")} VARCHAR(255) NOT NULL,
                ${dialect.quoteIdentifier("payload")} TEXT,
                ${dialect.quoteIdentifier("created_at")} DATETIME(3) NOT NULL DEFAULT NOW(3),
                INDEX ${dialect.quoteIdentifier("idx_${outboxTableName}_channel_id")} (${dialect.quoteIdentifier("channel")}, ${dialect.quoteIdentifier("id")})
            );
            """.trimIndent(),
        )
    }

    override fun renderDropTriggerDdl(trigger: NotifyTrigger<*>): List<String> {
        val dialect = MySqlDialect
        return trigger.events.map { event ->
            "DROP TRIGGER IF EXISTS ${dialect.quoteIdentifier(eventTriggerName(trigger, event))};"
        }
    }

    private fun eventTriggerName(trigger: NotifyTrigger<*>, event: TriggerEvent): String =
        if (trigger.events.size > 1) "${trigger.name}_${event.name.lowercase()}" else trigger.name
}

/**
 * Oracle has no native pub/sub either; like MySQL, the trigger writes into the
 * `aggo_notifications` outbox table. Oracle's `CREATE OR REPLACE TRIGGER` does
 * support multiple events in one declaration, so a single trigger suffices.
 */
object OracleTriggerDialect : TriggerDialect {

    override fun renderNotifyTriggerDdl(trigger: NotifyTrigger<*>, outboxTableName: String): List<String> {
        val dialect = OracleDialect
        val table = dialect.qualifyTableName(trigger.table.name)
        val triggerName = dialect.quoteIdentifier(trigger.name)
        val outbox = dialect.requireValidChannelName(outboxTableName)
        val timing = trigger.timing.name.uppercase()
        val events = trigger.events.joinToString(" OR ") { it.name.uppercase() }
        val channel = dialect.requireValidChannelName(trigger.channel.name)

        // :NEW is empty on DELETE and :OLD is empty on INSERT — a combined trigger must
        // branch on the firing operation (DELETING) rather than always selecting one side.
        val payloadExpr = "CASE WHEN DELETING " +
            "THEN (${rewriteRowImage(trigger.payloadSql, useOld = true, rowPrefix = ":")}) " +
            "ELSE (${rewriteRowImage(trigger.payloadSql, useOld = false, rowPrefix = ":")}) END"

        return listOf(
            """
            CREATE OR REPLACE TRIGGER $triggerName
            $timing $events ON $table
            FOR EACH ROW
            BEGIN
                INSERT INTO $outbox (channel, payload, created_at)
                VALUES ('$channel', $payloadExpr, SYSTIMESTAMP);
            END;
            """.trimIndent(),
        )
    }

    override fun renderOutboxTableDdl(outboxTableName: String): List<String> {
        val outbox = OracleDialect.requireValidChannelName(outboxTableName)
        return listOf(
            """
            CREATE TABLE $outbox (
                id         NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                channel    VARCHAR2(255) NOT NULL,
                payload    CLOB,
                created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX idx_${outboxTableName}_channel_id ON $outbox (channel, id)",
        )
    }

    override fun renderDropTriggerDdl(trigger: NotifyTrigger<*>): List<String> =
        listOf("DROP TRIGGER ${OracleDialect.quoteIdentifier(trigger.name)};")
}

/** Name of the outbox table managed by Aggo for dialects without native pub/sub. */
internal const val OUTBOX_TABLE = "aggo_notifications"

/**
 * Replays [payloadSql] against the row image the firing operation actually provides:
 * every `NEW`/`OLD` token is rewritten to `OLD` (when [useOld]) or `NEW`, optionally
 * carrying a dialect-specific pseudo-record [rowPrefix] (`":"` for Oracle, `""` elsewhere).
 *
 * A single trigger body must serve INSERT, UPDATE, and DELETE alike, but `NEW` is
 * unpopulated on DELETE and `OLD` is unpopulated on INSERT — whichever reference the
 * author wrote has to be swapped to match the operation that is actually running.
 */
private fun rewriteRowImage(payloadSql: String, useOld: Boolean, rowPrefix: String = ""): String =
    Regex("\\b(?:NEW|OLD)\\b").replace(payloadSql) { rowPrefix + if (useOld) "OLD" else "NEW" }

/**
 * Validates that [name] is safe to splice as a single-quoted SQL string literal
 * inside generated trigger bodies (channel names are passed to `pg_notify`/`INSERT`
 * as literals, not bound parameters, because they live inside `plpgsql`/outbox DDL).
 * Delegates to [requireValidIdentifier] — the same allowlist [NotifyChannel] enforces —
 * so a channel name can never contain a quote, semicolon, or other SQL metacharacter.
 */
private fun SqlDialect.requireValidChannelName(name: String): String {
    requireValidIdentifier(name)
    return name
}
