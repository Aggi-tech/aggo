package com.aggitech.aggo.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.psiUtil.anyDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.getCallNameExpression

/**
 * Flags `select(Table) { ... }` builder blocks that configure neither
 * `where { }` nor `limit(...)` (directly, or transitively via `orderBy` +
 * paging helpers — this rule only requires *one* of the two bounding knobs
 * to be present anywhere in the block).
 *
 * Such a query renders as a bare `SELECT col, col, ... FROM "table"` and
 * [com.aggitech.aggo.runtime.Session.fetchAll] decodes *every* row into
 * memory through `Table.fromRow`. That's fine for a handful of lookup tables
 * — a startup-time outage for anything that grows past a few thousand rows.
 *
 * This is intentionally `Warning`, not `Defect`: some tables genuinely are
 * meant to be read whole (small enum-like reference tables). Suppress locally
 * with `@Suppress("UnboundedSelectRule")` when that's a deliberate choice —
 * the annotation itself documents the decision for the next reader.
 */
class UnboundedSelectRule(config: Config = Config.empty) : Rule(config) {

    override val issue: Issue = Issue(
        javaClass.simpleName,
        Severity.Warning,
        "select(Table) { } with neither where { } nor limit(...) loads the entire table into " +
            "memory on every call. Add a filter or a bound — or suppress this rule locally with " +
            "@Suppress(\"UnboundedSelectRule\") if the full scan is genuinely intentional (e.g. a " +
            "small reference table), so the choice is documented for the next reader.",
        Debt.FIVE_MINS,
    )

    private val boundingCallNames = setOf("where", "limit")

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        if (expression.getCallNameExpression()?.text != "select") return
        val lambda = expression.lambdaArguments.firstOrNull()?.getLambdaExpression() ?: return
        val body = lambda.bodyExpression ?: return

        val isBounded = body.anyDescendantOfType<KtCallExpression> { call ->
            call.getCallNameExpression()?.text in boundingCallNames
        }
        if (!isBounded) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(expression),
                    "select { } has no where { } or limit(...) — it will fetch and decode the whole table on every call.",
                ),
            )
        }
    }
}
