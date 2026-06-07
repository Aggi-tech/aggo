package com.aggitech.aggo.schema

import com.aggitech.aggo.dialect.requireValidIdentifier
import com.aggitech.aggo.notify.NotifyChannel

/** When the trigger fires relative to the row operation. */
enum class TriggerTiming { Before, After }

/** Which row operation(s) the trigger fires on. */
enum class TriggerEvent { Insert, Update, Delete }

/**
 * Declares a trigger on [table] that emits a notification on [channel] whenever
 * one of [events] occurs, with a payload derived from the SQL expression [payloadSql].
 *
 * [payloadSql] is a literal SQL expression referencing `NEW`/`OLD`
 * (e.g. `"NEW.id"`, `"NEW.id::text"`, `"NEW.email || ':' || NEW.id"`). It is
 * embedded into the generated trigger body by [com.aggitech.aggo.dialect.TriggerDialect] —
 * the integrator is responsible for ensuring the expression is valid for the target database.
 *
 * No reflection and no domain mapping happens here — this is pure schema metadata,
 * declared alongside the table object, never inside it (mirrors the [Table] /
 * [ForeignKey] separation).
 *
 * ```kotlin
 * val UserEventsChannel = NotifyChannel("user_events", StringNotifyCodec)
 *
 * val UserInsertedTrigger = NotifyTrigger(
 *     name       = "trg_notify_user_inserted",
 *     table      = UsersTable,
 *     channel    = UserEventsChannel,
 *     events     = setOf(TriggerEvent.Insert),
 *     payloadSql = "NEW.id",
 * )
 * ```
 */
data class NotifyTrigger<E : Any>(
    val name: String,
    val table: Table<E>,
    val channel: NotifyChannel<*>,
    val timing: TriggerTiming = TriggerTiming.After,
    val events: Set<TriggerEvent> = setOf(TriggerEvent.Insert),
    val payloadSql: String = "NULL",
    val forEachRow: Boolean = true,
) {
    init {
        requireValidIdentifier(name)
        require(events.isNotEmpty()) { "events must not be empty" }
    }
}
