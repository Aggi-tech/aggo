---
name: aggo-query-dsl
description: Guide for Aggo's query DSL — select, insert, update, delete, selectProjection, aggregate, joins (leftJoin), WHERE operators, ORDER BY, LIMIT/OFFSET, and Session execution methods. Use when writing queries or diagnosing DSL/rendering issues.
---

# Aggo Query DSL Skill

You are writing or debugging Aggo queries. All queries are executed inside `aggo.read {}` or `aggo.tx {}` blocks via `Session`.

## SELECT — fetchAll / fetchOne / stream

```kotlin
// All rows
val users: List<User> = aggo.read {
    fetchAll(UsersTable) {
        where { UsersTable.active eq true }
        orderBy { UsersTable.name.asc() }
        limit(100)
        offset(0)
    }
}

// Single row or null
val user: User? = aggo.read {
    fetchOne(UsersTable) { where { UsersTable.email eq email } }
}

// Cold Flow — for large result sets
aggo.read {
    stream(ReportsTable) { where { ReportsTable.year eq 2025 } }
        .filter { it.revenue > 0 }
        .collect { report -> export(report) }
}
```

## INSERT

```kotlin
// Full entity insert
aggo.tx { insert(UsersTable, user) }

// Partial insert (column setTo)
aggo.tx {
    insert(UsersTable) {
        UsersTable.email  setTo email
        UsersTable.name   setTo name
        UsersTable.active setTo true
    }
}

// Insert and return generated PK
val newId: Tsid? = aggo.tx {
    insertReturning(UsersTable, UsersTable.id) {
        UsersTable.email  setTo email
        UsersTable.name   setTo name
        UsersTable.active setTo true
    }
}
```

`insert()` skips columns marked `isGenerated = true` — database handles DEFAULT/sequences.

## UPDATE

```kotlin
val rowsAffected: Long = aggo.tx {
    update(UsersTable) {
        UsersTable.active setTo false
        UsersTable.name   setTo "Deactivated"
        where { UsersTable.id eq userId }
    }
}
check(rowsAffected == 1L) { "user $userId not found" }
```

## DELETE

```kotlin
// With WHERE
aggo.tx { delete(UsersTable) { where { UsersTable.id eq userId } } }

// All rows (no WHERE needed)
aggo.tx { delete(UsersTable) }
```

## WHERE operators

```kotlin
// Equality / inequality
UsersTable.active eq true
UsersTable.status neq "CLOSED"

// Comparisons
UsersTable.score gt 90
UsersTable.score gte 90
UsersTable.score lt 50
UsersTable.score lte 50

// Range
UsersTable.score between (0 to 100)

// String pattern (LIKE / ILIKE)
UsersTable.name like "%alice%"
UsersTable.name ilike "%alice%"

// Null checks
UsersTable.deletedAt.isNull()
UsersTable.deletedAt.isNotNull()

// IN / NOT IN
UsersTable.status `in` listOf("ACTIVE", "PENDING")
UsersTable.status notIn listOf("CLOSED")

// Logical composition
and(UsersTable.active eq true, UsersTable.score gt 50)
or(UsersTable.role eq "ADMIN", UsersTable.role eq "EDITOR")
not(UsersTable.active eq true)

// Complex compound WHERE
where {
    and(
        UsersTable.active eq true,
        or(
            UsersTable.role eq "ADMIN",
            UsersTable.score gt 90
        )
    )
}
```

## ORDER BY

```kotlin
orderBy { UsersTable.name.asc() }
orderBy { UsersTable.createdAt.desc() }

// Multi-column
orderBy {
    UsersTable.status.asc()
    UsersTable.name.asc()
}
```

## PROJECTION SELECT — partial column read

Use when you don't need the full entity and want to select only specific columns.

```kotlin
val q = selectProjection(UsersTable, UsersTable.id, UsersTable.email) {
    where { UsersTable.active eq true }
    limit(100)
}

val dtos: List<UserDto> = aggo.read {
    fetchProjection(q).map { row ->
        UserDto(
            id    = row[UsersTable.id]!!,
            email = row[UsersTable.email]!!,
        )
    }
}
```

Or inline form:

```kotlin
aggo.read {
    fetchProjection(UsersTable, UsersTable.id, UsersTable.email) {
        where { UsersTable.active eq true }
    }
}
```

## AGGREGATE SELECT — GROUP BY / aggregate functions

```kotlin
val total  = count(OrdersTable.id)     `as` "total"
val avgAmt = avg(OrdersTable.amount)   `as` "avg_amount"
val minAmt = min(OrdersTable.amount)   `as` "min_amount"
val maxAmt = max(OrdersTable.amount)   `as` "max_amount"
val sumAmt = sum(OrdersTable.amount)   `as` "sum_amount"

val results: List<AggRow> = aggo.read {
    fetchAggregate(aggregate(OrdersTable) {
        project(total)
        project(avgAmt)
        where  { OrdersTable.active eq true }
        groupBy(OrdersTable.customerId)
        having { count(OrdersTable.id) gt 0L }
        orderBy { total.desc() }
        limit(50)
    })
}

results.map { row -> row[total]!! to row[avgAmt]!! }
```

## LEFT JOIN

```kotlin
val rows: List<JoinedRow<Order, User?>> = aggo.read {
    fetchAllJoined(
        OrdersTable.leftJoin(UsersTable) { OrdersTable.userId eq UsersTable.id }
            .where { OrdersTable.status eq "PENDING" }
            .limit(50)
    )
}

rows.forEach { (order, user) ->
    println("${order.id} placed by ${user?.name ?: "unknown"}")
}
```

Right side is `null` when no matching row exists (standard LEFT JOIN semantics).

Streaming form: `streamJoined(query)` returns `Flow<JoinedRow<L, R>>`.

## Pre-building queries (reuse across calls)

```kotlin
val activeUsersQuery = select(UsersTable) {
    where { UsersTable.active eq true }
    orderBy { UsersTable.name.asc() }
}

// Reuse:
aggo.read { fetchAll(activeUsersQuery) }
```

## Session transaction model

```kotlin
// Read-only (autocommit)
aggo.read { session ->
    val users = session.fetchAll(UsersTable)
}

// Transactional — all statements share one connection
aggo.tx { session ->
    val updated = session.update(UsersTable) {
        UsersTable.name setTo newName
        where { UsersTable.id eq id }
    }
    session.insert(AuditTable) { AuditTable.event setTo "name.changed" }
}
```

`aggo.tx` commits on success, rolls back on throw. Rollback errors are suppressed — callers always catch the original exception type.

## Common mistakes

| Mistake | Fix |
|---------|-----|
| Collecting a `stream {}` Flow outside the `read {}` / `tx {}` block | Always collect inside the same block — connection is released when block ends |
| Omitting WHERE on update/delete | Intentional for full-table ops, but double-check scope |
| Using `fetchAll` for millions of rows | Use `stream {}` to avoid loading all into memory |
| Constructing `$1` placeholders manually | Never — use `ctx.bind()` inside a `RenderContext` (framework only) |
