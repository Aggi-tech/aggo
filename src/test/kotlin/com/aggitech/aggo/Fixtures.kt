package com.aggitech.aggo

import com.aggitech.aggo.schema.BooleanCodec
import com.aggitech.aggo.schema.Codec
import com.aggitech.aggo.schema.InstantCodec
import com.aggitech.aggo.schema.IntCodec
import com.aggitech.aggo.schema.StringCodec
import com.aggitech.aggo.schema.Table
import com.aggitech.aggo.schema.ValueClassCodec
import io.r2dbc.spi.Row
import java.time.Instant

/** Tiny @JvmInline-shaped wrapper used in tests to exercise ValueClassCodec. */
@JvmInline value class Email(val raw: String)

val EmailCodec: Codec<Email> = ValueClassCodec(StringCodec, ::Email, Email::raw)

data class Person(
    val id: Int,
    val email: Email,
    val name: String,
    val active: Boolean,
    val createdAt: Instant,
)

object People : Table<Person>("people") {
    val id = column("id", IntCodec, isPrimaryKey = true, isGenerated = true) { it.id }
    val email = column("email", EmailCodec) { it.email }
    val fullName = column("name", StringCodec) { it.name }
    val active = column("active", BooleanCodec) { it.active }
    val createdAt = column("created_at", InstantCodec, isGenerated = true) { it.createdAt }

    override fun fromRow(row: Row): Person = Person(
        id = id.required(row),
        email = email.required(row),
        name = fullName.required(row),
        active = active.required(row),
        createdAt = createdAt.required(row),
    )
}

data class Pet(
    val id: Int,
    val ownerId: Int,
    val name: String,
)

object Pets : Table<Pet>("pets") {
    val id = column("id", IntCodec, isPrimaryKey = true, isGenerated = true) { it.id }
    val ownerId = column("owner_id", IntCodec) { it.ownerId }
    val petName = column("name", StringCodec) { it.name }

    override fun fromRow(row: Row): Pet = Pet(
        id = id.required(row),
        ownerId = ownerId.required(row),
        name = petName.required(row),
    )
}
