package com.aggitech.aggo.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.psiUtil.anyDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.getCallNameExpression

/**
 * Flags `update(Table) { ... }` / `delete(Table) { ... }` builder blocks (and
 * fully-qualified `com.aggitech.aggo.dsl.update`/`delete` calls) that never
 * call `where { }`.
 *
 * [com.aggitech.aggo.dsl.update] / [com.aggitech.aggo.dsl.delete] render
 * `UPDATE "table" SET ...` / `DELETE FROM "table"` when no predicate is
 * supplied — every row, no confirmation, no compiler error, because
 * `where { }` is an optional builder call (`query/Queries.kt`:
 * `Update<E>(... val where: Predicate? = null)`). This is the single most
 * common way an Aggo-backed service silently destroys production data, and
 * the kind of thing a code reviewer reads past because the call *looks*
 * complete.
 *
 * If a full-table operation is genuinely intended, make that explicit —
 * e.g. `where { Orders.id.isNotNull() }` — so the next reader (and this rule)
 * sees deliberate intent rather than an omission.
 */
class UpdateDeleteRequiresWhereRule(config: Config = Config.empty) : Rule(config) {

    override val issue: Issue = Issue(
        javaClass.simpleName,
        Severity.Defect,
        "update { } / delete { } without where { } affects every row in the table. " +
            "Add a where { } filter — or, if a full-table operation is genuinely " +
            "intended, make that explicit (e.g. where { Table.id.isNotNull() }) so " +
            "reviewers and this rule see deliberate intent rather than an omission.",
        Debt.TEN_MINS,
    )

    private val mutationNames = setOf("update", "delete")
    private val mutationFqNames = setOf("com.aggitech.aggo.dsl.update", "com.aggitech.aggo.dsl.delete")

    private var fileImportsAggoMutation = false

    override fun visitKtFile(file: KtFile) {
        fileImportsAggoMutation = file.importDirectives.any(::isAggoMutationImport)
        super.visitKtFile(file)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val name = expression.getCallNameExpression()?.text ?: return
        if (name !in mutationNames) return
        if (!fileImportsAggoMutation && !isFullyQualifiedAggoMutation(expression)) return

        val lambda = expression.lambdaArguments.firstOrNull()?.getLambdaExpression() ?: run {
            report(missingLambdaSmell(expression, name))
            return
        }
        val body = lambda.bodyExpression ?: return

        val hasWhere = body.anyDescendantOfType<KtCallExpression> { call ->
            call.getCallNameExpression()?.text == "where"
        }
        if (!hasWhere) {
            report(CodeSmell(issue, Entity.from(expression), "'$name { }' has no where { } — every row in the table will be affected."))
        }
    }

    private fun missingLambdaSmell(expression: KtCallExpression, name: String) = CodeSmell(
        issue,
        Entity.from(expression),
        "'$name(...)' has no builder block at all, so it cannot carry a where { } filter — every row in the table will be affected.",
    )

    private fun isAggoMutationImport(import: KtImportDirective): Boolean {
        val path = import.importedFqName?.asString() ?: return false
        return path in mutationFqNames || path == "com.aggitech.aggo.dsl.*"
    }

    /** Best-effort fallback for fully-qualified calls bypassing the import (e.g. `com.aggitech.aggo.dsl.update(...)`). */
    private fun isFullyQualifiedAggoMutation(expression: KtCallExpression): Boolean =
        mutationFqNames.any { fqName -> expression.text.startsWith(fqName) }
}
