package com.aggitech.aggo

import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.migration.addUniqueConstraintsSql
import com.aggitech.aggo.migration.createTableSql
import com.aggitech.aggo.migration.migrationSchema
import com.aggitech.aggo.runtime.ConstraintError
import com.aggitech.aggo.runtime.ConstraintKind
import com.aggitech.aggo.runtime.Query
import com.aggitech.aggo.runtime.extractConstraintName
import com.aggitech.aggo.runtime.flatMap
import com.aggitech.aggo.runtime.map
import com.aggitech.aggo.schema.Checks
import com.aggitech.aggo.schema.ForeignKeyAction
import com.aggitech.aggo.schema.Table
import com.aggitech.aggo.schema.constraintErrorMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.r2dbc.spi.Row
import java.math.BigDecimal

private data class FluentUser(
    val id: String,
    val email: String,
    val status: UserStatus,
    val balance: BigDecimal?,
)

private enum class UserStatus { ACTIVE, BLOCKED }

private object FluentUsers : Table<FluentUser>("fluent_users") {
    val id = varchar("id", 26)
        .required()
        .primaryKey()
        .check(Checks.ulid(), key = "user.id.invalid") { it.id }

    val email = varchar("email", 255)
        .required()
        .unique(key = "user.email.taken")
        .check(Checks.email(), key = "user.email.invalid") { it.email }

    val status = enumName<UserStatus>("status")
        .required() { it.status }

    val balance = decimal("balance", precision = 12, scale = 2)
        .optional() { it.balance }

    override fun fromRow(row: Row): FluentUser =
        FluentUser(id.required(row), email.required(row), status.required(row), balance.nullable(row))
}

private data class FluentProfile(val id: String, val userId: String)

private object FluentProfiles : Table<FluentProfile>("fluent_profiles") {
    val id = varchar("id", 26)
        .required()
        .primaryKey()
        .check(Checks.ulid()) { it.id }

    val userId = varchar("user_id", 26)
        .required()
        .references(
            FluentUsers.id,
            onDelete = ForeignKeyAction.CASCADE,
            key = "profile.user.missing",
        ) { it.userId }

    override fun fromRow(row: Row): FluentProfile =
        FluentProfile(id.required(row), userId.required(row))
}

class FluentSchemaTest : StringSpec({

    "fluent schema builder emits checks, unique constraints, enums and nullability" {
        val sql = FluentUsers.createTableSql(PostgresDialect)

        sql shouldContain "\"id\" VARCHAR(26) NOT NULL"
        sql shouldContain "\"email\" VARCHAR(255) NOT NULL"
        sql shouldContain "\"status\" VARCHAR(64) NOT NULL"
        sql shouldContain "\"balance\" NUMERIC(12, 2),"
        sql shouldContain "CONSTRAINT \"chk_fluent_users_id\" CHECK"
        sql shouldContain "CONSTRAINT \"chk_fluent_users_email\" CHECK"
        sql shouldContain "CONSTRAINT \"chk_fluent_users_status\" CHECK (\"status\" IN ('ACTIVE', 'BLOCKED'))"
        sql shouldContain "CONSTRAINT \"uq_fluent_users_email\" UNIQUE (\"email\")"
        sql shouldContain "PRIMARY KEY (\"id\")"
    }

    "unique constraints are included in migration snapshots and alter-table SQL" {
        val table = migrationSchema("v1", listOf(FluentUsers), PostgresDialect).tables.single()

        table.uniques.single().name shouldBe "uq_fluent_users_email"
        table.uniques.single().columns shouldBe listOf("email")
        FluentUsers.addUniqueConstraintsSql(PostgresDialect).single() shouldBe
            """ALTER TABLE "fluent_users" ADD CONSTRAINT "uq_fluent_users_email" UNIQUE ("email");"""
    }

    "constraintErrorMap maps check unique and foreign key names to typed errors" {
        val map = constraintErrorMap(FluentUsers, FluentProfiles)

        val check = map.map(RuntimeException("""violates check constraint "chk_fluent_users_email""""))
        check shouldBe ConstraintError(
            key = "user.email.invalid",
            constraintName = "chk_fluent_users_email",
            kind = ConstraintKind.CHECK,
            table = "fluent_users",
            column = "email",
            cause = check.cause,
        )

        val unique = map.map(RuntimeException("""duplicate key violates unique constraint "uq_fluent_users_email""""))
        (unique as ConstraintError).key shouldBe "user.email.taken"
        unique.kind shouldBe ConstraintKind.UNIQUE

        val fk = map.map(RuntimeException("""violates foreign key constraint "fk_fluent_profiles_user_id""""))
        (fk as ConstraintError).key shouldBe "profile.user.missing"
        fk.kind shouldBe ConstraintKind.FOREIGN_KEY
    }

    "constraint names can be extracted from common database messages" {
        extractConstraintName("""violates unique constraint "uq_fluent_users_email"""".let(::RuntimeException)) shouldBe
            "uq_fluent_users_email"
        extractConstraintName("violates check constraint chk_fluent_users_email".let(::RuntimeException)) shouldBe
            "chk_fluent_users_email"
    }

    "Query supports map and flatMap" {
        val result: Query<Int, String> = Query.Success(20)
        val mapped = result
            .map { it + 1 }
            .flatMap { Query.Success(it * 2) }

        mapped shouldBe Query.Success(42)
        Query.Failure("bad").map { 1 } shouldBe Query.Failure("bad")
    }
})
