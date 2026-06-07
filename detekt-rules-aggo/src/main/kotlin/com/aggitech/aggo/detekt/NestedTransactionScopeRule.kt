package com.aggitech.aggo.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getCallNameExpression

/**
 * Flags `aggo.tx { }` / `aggo.read { }` (or bare `tx { }` / `read { }` on an
 * `Aggo` receiver) nested inside another `tx { }` / `read { }` block.
 *
 * Per CLAUDE.md's transaction model, *both* scopes "Acquire a connection from
 * the pool[, w]rap it in a Session[, and r]elease... in finally — always".
 * Nesting them means the inner call blocks on the pool *while the outer
 * connection is still checked out* — on a saturated pool that's a deadlock,
 * and on a healthy one it silently runs the "nested" work in its own,
 * separate connection/transaction (so an outer `tx { }` rollback will NOT
 * undo what the inner block committed). Either way the nesting visually
 * promises atomicity it does not deliver.
 *
 * Fix: thread the outer `Session` down as a parameter and call
 * `session.fetchAll(...)` / `session.insert(...)` / etc. directly — never
 * open a second `tx { }` / `read { }` scope from inside one.
 */
class NestedTransactionScopeRule(config: Config = Config.empty) : Rule(config) {

    override val issue: Issue = Issue(
        javaClass.simpleName,
        Severity.Defect,
        "Nested tx { } / read { } blocks each acquire their own pooled connection and Session " +
            "— this can deadlock a saturated pool, or silently split the work across separate, " +
            "non-atomic transactions. Pass the outer Session down as a parameter instead of " +
            "opening a new scope from inside one.",
        Debt.TWENTY_MINS,
    )

    private val scopeNames = setOf("tx", "read")

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val outerName = expression.getCallNameExpression()?.text ?: return
        if (outerName !in scopeNames) return

        val lambda = expression.lambdaArguments.firstOrNull()?.getLambdaExpression() ?: return
        val body = lambda.bodyExpression ?: return

        body.collectDescendantsOfType<KtCallExpression> { call ->
            call !== expression && call.getCallNameExpression()?.text in scopeNames
        }.forEach { nested ->
            val innerName = nested.getCallNameExpression()?.text
            report(
                CodeSmell(
                    issue,
                    Entity.from(nested),
                    "'$innerName { }' is nested inside an outer '$outerName { }' — reuse the outer " +
                        "Session (pass it as a parameter) instead of opening a second connection scope.",
                ),
            )
        }
    }
}
