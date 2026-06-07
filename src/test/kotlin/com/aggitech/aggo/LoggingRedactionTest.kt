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
import io.kotest.core.spec.style.BehaviorSpec
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

class LoggingRedactionTest : BehaviorSpec({

    given("a column marked as sensitive") {
        `when`("its value is bound by an INSERT") {
            then("only the sensitive value is redacted from logs") {
                val query = insert(Accounts) {
                    Accounts.email setTo "a@b"
                    Accounts.passwordHash setTo "argon2id\$v=19\$m=65536…"
                    Accounts.nickname setTo "anon"
                }

                QueryLog.redact(renderInsert(query, PostgresDialect).params) shouldBe
                    listOf("a@b", "<redacted>", "anon")
            }
        }

        `when`("its value is bound by an UPDATE predicate") {
            then("the sensitive assignment is redacted and the identifier remains visible") {
                val query = update(Accounts) {
                    Accounts.passwordHash setTo "fresh-hash"
                    where { Accounts.id eq 7 }
                }

                QueryLog.redact(renderUpdate(query, PostgresDialect).params) shouldBe
                    listOf("<redacted>", 7)
            }
        }

        `when`("it is compared with a literal in a SELECT predicate") {
            then("the literal is redacted") {
                val query = select(Accounts) {
                    where { Accounts.passwordHash eq "secret-pw" }
                }

                QueryLog.redact(renderSelect(query, PostgresDialect).params) shouldBe listOf("<redacted>")
            }
        }
    }

    given("a non-sensitive column whose value contains a security-related word") {
        `when`("the value is logged") {
            then("redaction remains metadata-driven and preserves the value") {
                val query = select(Accounts) {
                    where { Accounts.nickname eq "anything containing password" }
                }

                QueryLog.redact(renderSelect(query, PostgresDialect).params) shouldBe
                    listOf("anything containing password")
            }
        }
    }
})
