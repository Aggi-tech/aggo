package com.aggitech.aggo.runtime

/**
 * V-3: opt-in marker for APIs that bypass Aggo's safety net (raw SQL, raw
 * Connection access). Callers must annotate the call site with
 * `@OptIn(AggoUnsafe::class)` or propagate the requirement with
 * `@AggoUnsafe` themselves.
 *
 * The level is [RequiresOptIn.Level.ERROR] on purpose — these are the
 * primitives most likely to enable SQL injection or break the transaction
 * model, so silent usage must be impossible.
 */
@RequiresOptIn(
    message = "This API bypasses Aggo's codec/identifier safety. Opt in explicitly or refactor through the DSL.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class AggoUnsafe
