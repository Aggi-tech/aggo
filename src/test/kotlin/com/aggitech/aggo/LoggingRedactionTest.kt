package com.aggitech.aggo

import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.dsl.eq
import com.aggitech.aggo.dsl.insert
import com.aggitech.aggo.dsl.update
import com.aggitech.aggo.dsl.where
import com.aggitech.aggo.render.renderInsert
import com.aggitech.aggo.render.renderSelect
import com.aggitech.aggo.render.renderUpdate
import com.aggitech.aggo.runtime.QueryLog
import com.aggitech.aggo.schema.IntCodec
import com.aggitech.aggo.schema.StringCodec
import com.aggitech.aggo.schema.Table
import com.aggitech.aggo.dsl.select
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.r2dbc.spi.Row

private data class Account(val id: Int, val email: String, val passwordHash: String, val nickname: String)

private object Accounts : Table<Account>("accounts") {
    val id = column("id", IntCodec, isPrimaryKey = true) { it.id }
    val email = column("email", StringCodec) { it.email }
    val passwordHash = column("password_hash", StringCodec, sensitive = true) { it.passwordHash }
    val nickname = column("nickname", StringCodec) { it.nickname }

    override fun fromRow(row: Row): Account = Account(
        id = id.readRequired(row),
        email = email.readRequired(row),
        passwordHash = passwordHash.readRequired(row),
        nickname = nickname.readRequired(row),
    )
}

class LoggingRedactionTest : StringSpec({

    "V-6: sensitive column has its INSERT bind value redacted in trace logs" {
        val q = insert(Accounts) {
            Accounts.email setTo "a@b"
            Accounts.passwordHash setTo "argon2id\$v=19\$m=65536…"
            Accounts.nickname setTo "anon"
        }
        val rendered = renderInsert(q, PostgresDialect)
        val redacted = QueryLog.redact(rendered.params)
        redacted shouldBe listOf("a@b", "<redacted>", "anon")
    }

    "V-6: sensitive column has its UPDATE bind value redacted in trace logs" {
        val q = update(Accounts) {
            Accounts.passwordHash setTo "fresh-hash"
            where { Accounts.id eq 7 }
        }
        val rendered = renderUpdate(q, PostgresDialect)
        val redacted = QueryLog.redact(rendered.params)
        redacted shouldBe listOf("<redacted>", 7)
    }

    "V-6: predicate `sensitive eq literal` redacts the literal but `non-sensitive eq literal` does not" {
        val q = select(Accounts) {
            where { Accounts.passwordHash eq "secret-pw" }
        }
        val rendered = renderSelect(q, PostgresDialect)
        QueryLog.redact(rendered.params) shouldBe listOf("<redacted>")

        val q2 = select(Accounts) {
            where { Accounts.nickname eq "anything containing password" }
        }
        val rendered2 = renderSelect(q2, PostgresDialect)
        // Note: under V-6 column-driven redaction, the substring 'password'
        // in a *non-sensitive* column's value MUST NOT be masked.
        QueryLog.redact(rendered2.params) shouldBe listOf("anything containing password")
    }
})
