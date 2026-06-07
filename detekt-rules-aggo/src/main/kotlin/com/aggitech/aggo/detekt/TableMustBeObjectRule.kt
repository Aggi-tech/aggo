package com.aggitech.aggo.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry

/**
 * Flags a `Table<E>` declared as `class` / `open class` / `abstract class`
 * rather than `object`.
 *
 * CLAUDE.md is explicit about why this matters: "Mutable state on Table after
 * construction" is forbidden because "Tables are singletons, must be
 * thread-safe" — `Column.equals`/`hashCode` are keyed on `(table.name,
 * column.name)` (Core contract #3) specifically so that one shared instance
 * can be safely reused as a map/set key across the render and runtime layers.
 * A `class` extending `Table` can be instantiated more than once (per request,
 * per test, per DI scope), which silently breaks that identity assumption and
 * invites per-instance mutable state that concurrent coroutines will race on —
 * exactly the class of bug "Zero reflection" / GraalVM constraints push you
 * toward avoiding entirely by construction.
 *
 * Fix: declare it as `object UsersTable : Table<User>("users") { ... }`.
 */
class TableMustBeObjectRule(config: Config = Config.empty) : Rule(config) {

    override val issue: Issue = Issue(
        javaClass.simpleName,
        Severity.Defect,
        "Table<E> must be declared as 'object', not 'class' — Aggo tables are shared, " +
            "thread-safe singletons whose column identity is keyed on (table.name, column.name) " +
            "(CLAUDE.md: \"Tables are singletons, must be thread-safe\"). A class allows multiple " +
            "instances and invites per-instance mutable state that concurrent sessions will race on.",
        Debt.TEN_MINS,
    )

    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)

        if (klass.isInterface() || klass.isEnum() || klass.isAnnotation()) return
        if (!extendsTable(klass)) return

        report(
            CodeSmell(
                issue,
                Entity.from(klass),
                "'${klass.name}' extends Table<…> but is declared as a class — declare it as " +
                    "'object ${klass.name ?: "MyTable"}' instead so it stays a single shared, thread-safe instance.",
            ),
        )
    }

    private fun extendsTable(klass: KtClass): Boolean =
        klass.superTypeListEntries.any(::referencesTable)

    private fun referencesTable(entry: KtSuperTypeListEntry): Boolean {
        val typeText = entry.typeReference?.text ?: return false
        // Matches bare `Table<Foo>`, qualified `schema.Table<Foo>`, and the
        // no-type-argument form some intermediate base classes might use.
        return typeText == "Table" ||
            typeText.startsWith("Table<") ||
            typeText.endsWith(".Table") ||
            typeText.contains(".Table<")
    }
}
