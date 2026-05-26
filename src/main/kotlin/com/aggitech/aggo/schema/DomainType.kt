package com.aggitech.aggo.schema

import com.aggitech.aggo.dialect.requireValidIdentifier

/**
 * Native API for PostgreSQL `DOMAIN` types.
 *
 * A DOMAIN is a named SQL type that wraps an existing one and attaches reusable
 * `CHECK` constraints. Reusing the same validation rules across many columns is
 * the canonical use case — declare it once with [DomainType.create] and the
 * migration generator emits the `CREATE DOMAIN …` statement before any
 * `CREATE TABLE` that references it.
 *
 * The factory composes a [MigratableCodec] from:
 *  - a [base] codec that handles the raw value (`StringCodec`, `BigDecimalCodec`, …)
 *  - optional [wrap] / [unwrap] lambdas for `@JvmInline value class` domain types
 *  - one or more [Checks] expressions that become the DOMAIN's constraint
 *
 * Each check helper produces a SQL boolean expression that takes a column name.
 * `DomainType` rewrites that placeholder to PostgreSQL's `VALUE` keyword — which
 * inside a DOMAIN definition refers to the value being inserted — so the same
 * `Checks.email()`, `Checks.length(max = 100)`, … helpers used on regular
 * columns are reused verbatim here.
 *
 * Example — email value class backed by a DOMAIN:
 *
 * ```kotlin
 * @JvmInline value class Email(val raw: String)
 *
 * val EmailCodec = DomainType.create(
 *     name   = "email_domain",
 *     base   = StringCodec,
 *     sqlBaseType = "VARCHAR(255)",
 *     wrap   = ::Email,
 *     unwrap = Email::raw,
 *     checks = listOf(Checks.email()),
 * )
 * ```
 *
 * Generated DDL:
 *
 * ```sql
 * CREATE DOMAIN "email_domain" AS VARCHAR(255)
 *     CHECK ("VALUE" ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$' AND char_length("VALUE") <= 255);
 * ```
 *
 * Example — non-value-class domain (use [createSimple] / pass identity lambdas):
 *
 * ```kotlin
 * val PositiveIntCodec = DomainType.createSimple(
 *     name   = "positive_int",
 *     base   = IntCodec,
 *     sqlBaseType = "INTEGER",
 *     checks = listOf(Checks.positive()),
 * )
 * ```
 */
object DomainType {

    /**
     * Build a [MigratableCodec] for a PostgreSQL DOMAIN type that wraps a
     * value class around the [base] codec's raw type.
     *
     * @param name DOMAIN identifier — must satisfy [com.aggitech.aggo.dialect.IDENTIFIER_REGEX].
     * @param base codec for the underlying primitive (driver type stays the same).
     * @param sqlBaseType the SQL type the DOMAIN extends, e.g. `TEXT`, `VARCHAR(255)`,
     *   `NUMERIC(10, 2)`, `INTEGER`. Validated against [SQL_TYPE_REGEX].
     * @param wrap factory from raw type to the domain value class.
     * @param unwrap extractor from the domain value class back to the raw type.
     * @param checks one or more [Checks] expressions composed with `AND`. Each
     *   lambda receives the placeholder name `VALUE` (PostgreSQL's keyword for
     *   the value being inserted into the DOMAIN).
     * @param createIfMissing when true, emits `CREATE DOMAIN … IF NOT EXISTS`-style
     *   DDL using a `DO $$ BEGIN … EXCEPTION WHEN duplicate_object … END $$;` block,
     *   because Postgres has no `CREATE DOMAIN IF NOT EXISTS` syntax. Defaults to true.
     */
    fun <V : Any, R : Any> create(
        name: String,
        base: Codec<R>,
        sqlBaseType: String,
        wrap: (R) -> V,
        unwrap: (V) -> R,
        checks: List<(String) -> String>,
        createIfMissing: Boolean = true,
    ): MigratableCodec<V> {
        requireValidIdentifier(name)
        requireValidSqlType(sqlBaseType)
        require(checks.isNotEmpty()) {
            "DomainType '$name' must declare at least one CHECK expression. " +
                "Use a regular MigratableCodec for unconstrained DOMAINs."
        }

        val checkBody = combineChecks(checks)
        val ddl = buildCreateDomainDdl(name, sqlBaseType, checkBody, createIfMissing)
        val wrapped = ValueClassCodec(base, wrap, unwrap)

        return object : MigratableCodec<V> {
            override val sqlType: Class<*> = base.sqlType
            override val ddlTypeName: String = name
            override val createDdl: String = ddl
            override fun encode(value: V?): Any? = wrapped.encode(value)
            override fun decode(raw: Any?): V? = wrapped.decode(raw)
        }
    }

    /**
     * Build a [MigratableCodec] for a DOMAIN whose Kotlin type matches the raw
     * driver type (no value-class wrapper). Useful when you want a reusable
     * constraint without introducing a new Kotlin type.
     */
    fun <V : Any> createSimple(
        name: String,
        base: Codec<V>,
        sqlBaseType: String,
        checks: List<(String) -> String>,
        createIfMissing: Boolean = true,
    ): MigratableCodec<V> = create(
        name = name,
        base = base,
        sqlBaseType = sqlBaseType,
        wrap = { it },
        unwrap = { it },
        checks = checks,
        createIfMissing = createIfMissing,
    )

    private fun combineChecks(checks: List<(String) -> String>): String {
        val raw = if (checks.size == 1) checks.single()(VALUE_PLACEHOLDER)
        else checks.joinToString(" AND ") { "(${it(VALUE_PLACEHOLDER)})" }
        // Checks.* helpers double-quote their column argument: `"$col"`. Inside
        // a DOMAIN check Postgres requires the bare keyword `VALUE`, not a
        // quoted identifier, so rewrite `"VALUE"` to `VALUE` post-hoc.
        return raw.replace("\"$VALUE_PLACEHOLDER\"", VALUE_PLACEHOLDER)
    }

    private fun buildCreateDomainDdl(
        name: String,
        sqlBaseType: String,
        checkBody: String,
        createIfMissing: Boolean,
    ): String {
        val quoted = "\"$name\""
        val core = "CREATE DOMAIN $quoted AS $sqlBaseType CHECK ($checkBody);"
        return if (!createIfMissing) core
        else buildString {
            append("DO ").append("\$do\$ BEGIN ")
            append(core.removeSuffix(";"))
            append("; EXCEPTION WHEN duplicate_object THEN NULL; END ").append("\$do\$;")
        }
    }

    /**
     * The token passed to [Checks] expressions inside DOMAIN definitions.
     *
     * Postgres requires the bare keyword `VALUE` (not a column name) inside
     * `CREATE DOMAIN … CHECK (…)`. `Checks` helpers quote their argument, so we
     * pass `VALUE` and let the quoting wrap it as `"VALUE"` — which Postgres
     * still resolves correctly to the keyword inside this context.
     */
    private const val VALUE_PLACEHOLDER = "VALUE"
}
