# Package com.aggitech.aggo.query

# JOIN Queries

Languages: English first, Portuguese below.

Aggo supports LEFT JOIN between two tables. The result is a typed `JoinedRow<L, R>`
pair where the right side is `null` when no matching row exists in the right table
(standard LEFT JOIN semantics).

This is the explicit Aggo replacement for Hibernate association loading. Aggo
does not create relation proxies and does not lazy-load collections.

## leftJoin

```kotlin
import com.aggitech.aggo.dsl.leftJoin
import com.aggitech.aggo.query.JoinedRow

val rows: List<JoinedRow<Order, User?>> = aggo.read {
    fetchAllJoined(
        OrdersTable.leftJoin(UsersTable) { OrdersTable.userId eq UsersTable.id }
    )
}

rows.forEach { (order, user) ->
    println("Order ${order.id} placed by ${user?.name ?: "unknown"}")
}
```

## Filtering a JOIN

Use `.where { }` on the result of `leftJoin`. You can reference columns from
both tables inside the block:

```kotlin
val rows = aggo.read {
    fetchAllJoined(
        OrdersTable.leftJoin(UsersTable) { OrdersTable.userId eq UsersTable.id }
            .where { (OrdersTable.status eq "PENDING") and UsersTable.active.isNotNull() }
    )
}
```

## Sorting a JOIN

```kotlin
val rows = aggo.read {
    fetchAllJoined(
        OrdersTable.leftJoin(UsersTable) { OrdersTable.userId eq UsersTable.id }
            .where { OrdersTable.status eq "PENDING" }
            .orderBy {
                OrdersTable.createdAt.desc()
                UsersTable.name.asc()
            }
    )
}
```

## Paging a JOIN

```kotlin
val page = aggo.read {
    fetchAllJoined(
        OrdersTable.leftJoin(UsersTable) { OrdersTable.userId eq UsersTable.id }
            .orderBy { OrdersTable.createdAt.desc() }
            .limit(20)
            .offset(pageIndex * 20)
    )
}
```

## Streaming JOIN results

For large joined result sets, use `streamJoined` to avoid loading everything
into memory:

```kotlin
aggo.read {
    streamJoined(
        OrdersTable.leftJoin(UsersTable) { OrdersTable.userId eq UsersTable.id }
            .where { OrdersTable.year eq 2025 }
    ).collect { (order, user) ->
        writeToCsv(order, user)
    }
}
```

## How null detection works

Aggo determines whether the right side of a LEFT JOIN is null by checking the
right table's primary key columns. If all primary key values are null in the
result row, `JoinedRow.right` is set to `null`.

If the right table has no declared primary keys, Aggo checks all right-side
columns. To ensure correct null detection, always declare `isPrimaryKey = true`
on your primary key column.

## Reading JoinedRow values

```kotlin
val rows: List<JoinedRow<Order, User?>> = aggo.read {
    fetchAllJoined(
        OrdersTable.leftJoin(UsersTable) { OrdersTable.userId eq UsersTable.id }
    )
}

for ((order, user) in rows) {
    val orderId    = order.id           // Order properties
    val userName   = user?.name         // User is nullable — safe-call required
    val userExists = user != null
}
```

## Chaining builders

`leftJoin`, `where`, `orderBy`, `limit`, and `offset` all return a new immutable
`JoinSelect` object. You can build a base query and derive specialized versions:

```kotlin
val base = OrdersTable
    .leftJoin(UsersTable) { OrdersTable.userId eq UsersTable.id }
    .orderBy { OrdersTable.createdAt.desc() }

val pending = base.where { OrdersTable.status eq "PENDING" }.limit(50)
val completed = base.where { OrdersTable.status eq "COMPLETED" }.limit(100)
```

## Aggo vs Hibernate associations

| Hibernate | Aggo |
|-----------|------|
| `@ManyToOne(fetch = LAZY)` | `leftJoin(...)` when the relation is needed |
| Proxy initialization | Nullable right side in `JoinedRow<L, R>` |
| Hidden N+1 risk | Query shape is explicit |
| Entity graph/fetch join | Explicit join DSL |
| Cascaded object graph | Separate table descriptors and queries |

Aggo's join API is intentionally small: it currently models typed LEFT JOINs
between two tables. For more complex reports, use projections, aggregates, or
database views with explicit table descriptors.

## Consultas JOIN

Aggo suporta `LEFT JOIN` entre duas tabelas. O resultado e um
`JoinedRow<L, R>`: o lado esquerdo sempre existe e o lado direito e nullable
quando nao ha correspondencia.

```kotlin
val rows: List<JoinedRow<Order, User?>> = aggo.read {
    fetchAllJoined(
        OrdersTable.leftJoin(UsersTable) {
            OrdersTable.userId eq UsersTable.id
        }
    )
}

for ((order, user) in rows) {
    println("${order.id}: ${user?.name ?: "sem usuario"}")
}
```

### Filtros e ordenacao

```kotlin
val rows = aggo.read {
    fetchAllJoined(
        OrdersTable
            .leftJoin(UsersTable) { OrdersTable.userId eq UsersTable.id }
            .where { OrdersTable.status eq "PENDING" }
            .orderBy {
                OrdersTable.createdAt.desc()
                UsersTable.name.asc()
            }
    )
}
```

### Streaming

```kotlin
aggo.read {
    streamJoined(
        OrdersTable.leftJoin(UsersTable) {
            OrdersTable.userId eq UsersTable.id
        }
    ).collect { (order, user) ->
        export(order, user)
    }
}
```

### Como o null do lado direito e detectado

Aggo verifica as chaves primarias da tabela direita. Se todas vierem `null`, o
`right` do `JoinedRow` fica `null`. Declare sempre `isPrimaryKey = true` na
tabela direita para evitar ambiguidades.

### Comparacao com Hibernate

No Hibernate, associacoes podem ser carregadas por lazy loading, fetch join ou
entity graph. Em Aggo, voce escolhe o join explicitamente. Isso deixa a query
mais verbosa que navegar `order.user`, mas evita SQL escondido e problemas de
proxy fora da sessao.
