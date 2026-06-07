package com.aggitech.aggo.notify

import com.aggitech.aggo.dialect.requireValidIdentifier

/**
 * Maps a logical [NotifyChannel] name to the physical name used on the wire.
 *
 * Use [SchemaPrefix] in schema-per-tenant deployments so each tenant gets its
 * own isolated `LISTEN`/`NOTIFY` namespace without redeclaring channels —
 * the same pattern [com.aggitech.aggo.dialect.MultiSchemaSqlDialect] uses for
 * table references.
 *
 * ```kotlin
 * val backend = AggoListener(connectionFactory, qualifier = ChannelNameQualifier.SchemaPrefix("acme"))
 * // logical "user_events" <-> physical "acme__user_events"
 * ```
 */
fun interface ChannelNameQualifier {
    fun qualify(channelName: String): String

    /** No qualification — the physical name equals the logical name. */
    object None : ChannelNameQualifier {
        override fun qualify(channelName: String): String = channelName
    }

    /** Prefixes every channel name with `"$schemaName__"`. */
    class SchemaPrefix(private val schemaName: String) : ChannelNameQualifier {
        init {
            requireValidIdentifier(schemaName)
        }

        override fun qualify(channelName: String): String = "${schemaName}__$channelName"
    }
}
