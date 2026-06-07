package com.aggitech.aggo.notify

import com.aggitech.aggo.dialect.OUTBOX_TABLE
import com.aggitech.aggo.dsl.SelectBuilder
import com.aggitech.aggo.dsl.and
import com.aggitech.aggo.dsl.gt
import com.aggitech.aggo.dsl.inList
import com.aggitech.aggo.runtime.Aggo
import com.aggitech.aggo.schema.LongCodec
import com.aggitech.aggo.schema.StringCodec
import com.aggitech.aggo.schema.Table
import io.r2dbc.spi.Row
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * [NotificationBackend] for MySQL/Oracle, which have no native pub/sub: a
 * [com.aggitech.aggo.schema.NotifyTrigger] there inserts into the managed
 * `aggo_notifications` outbox table (see [com.aggitech.aggo.dialect.TriggerDialect]),
 * and this listener polls it incrementally, replaying new rows as [RawNotification]s.
 *
 * Polling runs entirely through [aggo] — the regular DSL/[Aggo.session] surface,
 * not raw SQL — so it benefits from the same codec, pooling, and logging as the
 * rest of the application. Each poll is a single indexed `WHERE channel IN (...)
 * AND id > :lastSeenId ORDER BY id ASC LIMIT :batchSize` query.
 *
 * [outboxTableName] must match the name passed to
 * [com.aggitech.aggo.dialect.TriggerDialect.renderOutboxTableDdl] when the
 * migration plan was generated — otherwise the listener polls a table the
 * triggers never write to. Defaults to [OUTBOX_TABLE], the name Aggo uses
 * when no override is configured.
 */
class OutboxListener(
    private val aggo: Aggo,
    private val qualifier: ChannelNameQualifier = ChannelNameQualifier.None,
    private val pollInterval: Duration = 500.milliseconds,
    private val batchSize: Int = 100,
    outboxTableName: String = OUTBOX_TABLE,
) : NotificationBackend {

    private val outboxTable = OutboxTable(outboxTableName)

    override fun <P : Any> listen(channel: NotifyChannel<P>): Flow<Notification<P>> =
        listenRaw(channel)
            .filter { it.channel == qualifier.qualify(channel.name) }
            .map { raw ->
                Notification(
                    channel = channel.name,
                    payload = channel.codec.decode(raw.parameter),
                )
            }

    override fun listenRaw(vararg channels: NotifyChannel<*>): Flow<RawNotification> = flow {
        val qualifiedNames = channels.map { qualifier.qualify(it.name) }.toSet()
        var lastSeenId = fetchMaxId()

        while (true) {
            delay(pollInterval)
            val rows = fetchBatch(qualifiedNames, lastSeenId, batchSize)
            rows.forEach { row ->
                if (row.id > lastSeenId) lastSeenId = row.id
                emit(RawNotification(row.channel, row.payload, processId = 0))
            }
        }
    }

    private suspend fun fetchMaxId(): Long =
        aggo.session.fetchOne(outboxTable) {
            orderBy { outboxTable.id.desc() }
            limit(1)
        }?.id ?: 0L

    private suspend fun fetchBatch(channels: Set<String>, afterId: Long, limit: Int): List<OutboxRow> =
        aggo.session.fetchAll(outboxTable, fetchBatchBuilder(channels, afterId, limit))

    private fun fetchBatchBuilder(
        channels: Set<String>,
        afterId: Long,
        limit: Int,
    ): SelectBuilder<OutboxRow>.() -> Unit = {
        where { (outboxTable.channel inList channels) and (outboxTable.id gt afterId) }
        orderBy { outboxTable.id.asc() }
        limit(limit)
    }
}

/**
 * Managed outbox table descriptor — instantiated by [OutboxListener] for the
 * configured table name, never declared by integrators directly. A `class`
 * rather than the usual singleton `object` table because the name is
 * runtime-configurable (see [OutboxListener]'s `outboxTableName`).
 */
internal class OutboxTable(tableName: String) : Table<OutboxRow>(tableName) {
    val id = column("id", LongCodec, isPrimaryKey = true) { it.id }
    val channel = column("channel", StringCodec) { it.channel }
    val payload = column("payload", StringCodec, isNullable = true) { it.payload }

    override fun fromRow(row: Row): OutboxRow = OutboxRow(
        id = id.readRequired(row),
        channel = channel.readRequired(row),
        payload = payload.read(row),
    )
}

internal data class OutboxRow(val id: Long, val channel: String, val payload: String?)
