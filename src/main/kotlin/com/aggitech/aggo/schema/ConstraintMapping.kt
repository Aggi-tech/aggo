package com.aggitech.aggo.schema

import com.aggitech.aggo.runtime.ConstraintErrorDescriptor
import com.aggitech.aggo.runtime.ConstraintErrorMap
import com.aggitech.aggo.runtime.ConstraintKind

/**
 * Materializes every mappable constraint declared by this table.
 *
 * The returned descriptors connect database constraint names to application
 * keys declared in `check(key = ...)`, `unique(key = ...)`, and
 * `references(key = ...)`.
 */
fun Table<*>.constraintErrorDescriptors(): List<ConstraintErrorDescriptor> {
    val checkDescriptors = columns.flatMap { col ->
        col.checkConstraints.map { check ->
            ConstraintErrorDescriptor(
                key = check.effectiveKey(name, col.name),
                constraintName = check.effectiveName(name, col.name),
                kind = ConstraintKind.CHECK,
                table = name,
                column = col.name,
            )
        }
    }
    val uniqueDescriptors = columns.mapNotNull { col ->
        col.uniqueConstraint?.let { unique ->
            ConstraintErrorDescriptor(
                key = unique.effectiveKey(name, col.name),
                constraintName = unique.effectiveName(name, col.name),
                kind = ConstraintKind.UNIQUE,
                table = name,
                column = col.name,
            )
        }
    }
    val foreignKeyDescriptors = foreignKeys.map { fk ->
        ConstraintErrorDescriptor(
            key = fk.effectiveKey,
            constraintName = fk.effectiveName,
            kind = ConstraintKind.FOREIGN_KEY,
            table = fk.column.table.name,
            column = fk.column.name,
        )
    }
    return checkDescriptors + uniqueDescriptors + foreignKeyDescriptors
}

/**
 * Builds a constraint-name lookup for typed database error mapping.
 *
 * Pass the result to `Aggo.readQuery` or `Aggo.transaction` so database
 * constraint violations become [com.aggitech.aggo.runtime.ConstraintError].
 */
fun constraintErrorMap(vararg tables: Table<*>): ConstraintErrorMap =
    ConstraintErrorMap.of(tables.asIterable().flatMap { it.constraintErrorDescriptors() })

/** Iterable variant of [constraintErrorMap]. */
fun Iterable<Table<*>>.constraintErrorMap(): ConstraintErrorMap =
    ConstraintErrorMap.of(flatMap { it.constraintErrorDescriptors() })
