package com.aggitech.aggo.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/**
 * Custom rule set enforcing Aggo's Table / Session / Transaction contracts —
 * see `CLAUDE.md` "Core contracts" and "Transaction model". Each rule targets
 * a misconfiguration the Kotlin compiler lets through silently but that turns
 * into a runtime incident: a table-wide UPDATE/DELETE, a mutable Table
 * "singleton", a deadlocked connection pool, or an unbounded full-table scan.
 *
 * Activate individual rules under the `aggo` id in detekt.yml.
 */
class AggoRuleSetProvider : RuleSetProvider {

    override val ruleSetId: String = "aggo"

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            UpdateDeleteRequiresWhereRule(config),
            TableMustBeObjectRule(config),
            NestedTransactionScopeRule(config),
            UnboundedSelectRule(config),
        ),
    )
}
