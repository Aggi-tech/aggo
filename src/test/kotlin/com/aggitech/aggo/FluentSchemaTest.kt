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

private data class EnumRow(val status: UserStatus, val role: UserStatus)

private object EnumChecksTable : Table<EnumRow>("enum_checks") {
    // Uses the new idiomatic style: key is carried inside the CheckConstraint
    val status = enumName<UserStatus>("status")
        .check(Checks.notBlank(key = "status.blank"))
        .required { it.status }

    val role = enumName<UserStatus>("role")
        .required { it.role }

    override fun fromRow(row: Row): EnumRow =
        EnumRow(status.required(row), role.required(row))
}

// FE-5: exercises the new .checks(vararg CheckConstraint) API
private data class IdRow(val id: String)

private object MultiChecksTable : Table<IdRow>("multi_checks") {
    val id = varchar("id", 26)
        .checks(
            Checks.notBlank(key = "id.blank"),
            Checks.ulid(key = "id.format"),
        )
        .primaryKey()
        .required { it.id }

    override fun fromRow(row: Row): IdRow = IdRow(id.required(row))
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

    // FE-1: enumName + chained notBlank generates two distinct constraint names
    "enumName with chained check produces unique constraint names and correct expressions" {
        val sql = EnumChecksTable.createTableSql(PostgresDialect)

        sql shouldContain "CONSTRAINT \"chk_enum_checks_status_1\" CHECK (\"status\" IN ('ACTIVE', 'BLOCKED'))"
        sql shouldContain "CONSTRAINT \"chk_enum_checks_status_2\" CHECK (trim(\"status\") <> '')"
    }

    // FE-2: single-check enumName column keeps the legacy name (no suffix)
    "enumName with single check keeps legacy constraint name without suffix" {
        val sql = EnumChecksTable.createTableSql(PostgresDialect)

        sql shouldContain "CONSTRAINT \"chk_enum_checks_role\" CHECK (\"role\" IN ('ACTIVE', 'BLOCKED'))"
    }

    // FE-3: migrationSchema captures both constraints as separate MigrationCheck entries
    "enumName chained checks are materialized as two independent MigrationCheck entries" {
        val table = migrationSchema("v1", listOf(EnumChecksTable), PostgresDialect).tables.single()
        val statusChecks = table.checks.filter { it.name.contains("status") }

        statusChecks.size shouldBe 2
        statusChecks[0].name shouldBe "chk_enum_checks_status_1"
        statusChecks[0].expression shouldBe "\"status\" IN ('ACTIVE', 'BLOCKED')"
        statusChecks[1].name shouldBe "chk_enum_checks_status_2"
        statusChecks[1].expression shouldBe "trim(\"status\") <> ''"
    }

    // FE-4: constraintErrorMap resolves distinct keys for each chained check
    "constraintErrorMap resolves distinct keys for chained checks on the same column" {
        val errorMap = constraintErrorMap(EnumChecksTable)

        // check 1 (oneOf) — no explicit key → defaults to constraint name
        val enumViolation = errorMap.map(
            RuntimeException("""violates check constraint "chk_enum_checks_status_1"""")
        ) as ConstraintError
        enumViolation.constraintName shouldBe "chk_enum_checks_status_1"
        enumViolation.key shouldBe "chk_enum_checks_status_1"

        // check 2 (notBlank) — explicit key
        val blankViolation = errorMap.map(
            RuntimeException("""violates check constraint "chk_enum_checks_status_2"""")
        ) as ConstraintError
        blankViolation.constraintName shouldBe "chk_enum_checks_status_2"
        blankViolation.key shouldBe "status.blank"
    }

    // FE-5: .checks(vararg) generates indexed names and correct SQL
    "checks() vararg generates unique constraint names and expressions for each check" {
        val sql = MultiChecksTable.createTableSql(PostgresDialect)

        sql shouldContain "CONSTRAINT \"chk_multi_checks_id_1\" CHECK (trim(\"id\") <> '')"
        sql shouldContain "CONSTRAINT \"chk_multi_checks_id_2\" CHECK (char_length(\"id\") = 26"
    }

    // FE-6: constraintErrorMap resolves distinct keys for .checks(vararg) with keys
    "checks() vararg preserves per-check keys in constraintErrorMap" {
        val errorMap = constraintErrorMap(MultiChecksTable)

        val blankViolation = errorMap.map(
            RuntimeException("""violates check constraint "chk_multi_checks_id_1"""")
        ) as ConstraintError
        blankViolation.constraintName shouldBe "chk_multi_checks_id_1"
        blankViolation.key shouldBe "id.blank"

        val formatViolation = errorMap.map(
            RuntimeException("""violates check constraint "chk_multi_checks_id_2"""")
        ) as ConstraintError
        formatViolation.constraintName shouldBe "chk_multi_checks_id_2"
        formatViolation.key shouldBe "id.format"
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
