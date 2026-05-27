# Package com.aggitech.aggo.dsl

# Querying

Languages: English first, Portuguese below.

All read operations run inside an `aggo.read { }` block. The block receives a
`Session` as its receiver (`this`), and the connection is released automatically
when the block returns.

## fetchAll — load all matching rows

```kotlin
// All rows in the table
val users: List<User> = aggo.read { fetchAll(UsersTable) }

// With WHERE, ORDER BY, and LIMIT
val active = aggo.read {
    fetchAll(UsersTable) {
        where  { UsersTable.active eq true }
        orderBy { UsersTable.name.asc() }
        limit(20)
        offset(40)   // page 3, 20 rows per page
    }
}
```

`fetchAll` also has a typed-result overload. Passing a `ConstraintErrorMap`
changes the return type from `List<E>` to `Query<List<E>, AggoError>`:

```kotlin
val result: Query<List<User>, AggoError> =
    aggo.fetchAll(UsersTable, constraintErrorMap(UsersTable)) {
        where { UsersTable.active eq true }
    }
```

## fetchOne — load a single row

Returns the first matching row, or `null` if none exists. Always adds `LIMIT 1`
to the database query regardless of any limit in the builder.

```kotlin
val user: User? = aggo.read {
    fetchOne(UsersTable) { where { UsersTable.email eq email } }
}
```

## readQuery — typed read results

Use `readQuery` when you want query failures to be returned as values instead
of thrown exceptions. The return type is `Query<Success, Error>`:

```kotlin
val result: Query<List<User>, AggoError> = aggo.readQuery {
    fetchAll(UsersTable) {
        where { UsersTable.active eq true }
    }
}

val users = result.fold(
    onSuccess = { it },
    onFailure = { emptyList() },
)
```

`Query` is a small monadic result type:

```kotlin
val count: Query<Int, AggoError> = aggo.readQuery {
    fetchAll(UsersTable).size
}

val label: Query<String, AggoError> =
    count
        .map { "$it users" }
        .flatMap { Query.Success("result: $it") }
```

Use `fold` at application boundaries to convert typed database results into
HTTP responses, command results, or domain errors.

```kotlin
return result.fold(
    onSuccess = { users -> HttpResponse.ok(users) },
    onFailure = { error -> HttpResponse.serverError(error) },
)
```

### Mapping read errors from constraints

When a read runs SQL that can trigger constraints, such as `SELECT ... FOR
UPDATE` in custom statements or read-side functions, pass a constraint error
map:

```kotlin
val errorMap = constraintErrorMap(UsersTable)

val result = aggo.readQuery(errorMap) {
    fetchOne(UsersTable) { where { UsersTable.email eq email } }
}
```

Known constraint violations become `ConstraintError`; everything else becomes
`DatabaseError`.

## stream — process rows one at a time

Use `stream` when the result set is too large to hold in memory. Returns a cold
`Flow<E>` — rows are fetched incrementally as the flow is collected.

```kotlin
aggo.read {
    stream(ReportsTable) { where { ReportsTable.year eq 2025 } }
        .filter { it.revenue > 0 }
        .collect { report -> exportToCsv(report) }
}
```

The flow must be collected inside the same `read { }` block that created it.

## Pre-building queries

You can build a `Select` object ahead of time and execute it later:

```kotlin
import com.aggitech.aggo.dsl.select

val query = select(UsersTable) {
    where { UsersTable.active eq true }
    orderBy { UsersTable.createdAt.desc() }
    limit(50)
}

// Execute later — can be passed around, stored, composed
val users = aggo.read { fetchAll(query) }
val first = aggo.read { fetchOne(query) }
```

## WHERE operators — full reference

All operators are infix extensions on `Column<E, V>`. Use them inside any
`where { … }` block.

### Equality / inequality

```kotlin
where { UsersTable.active  eq  true }     // active = $1
where { UsersTable.role    ne  "ADMIN" }  // role <> $1
```

### Comparison (for Comparable types)

```kotlin
where { UsersTable.age  gt  18 }   // age > $1
where { UsersTable.age  gte 18 }   // age >= $1
where { UsersTable.age  lt  65 }   // age < $1
where { UsersTable.age  lte 65 }   // age <= $1
```

### String patterns

```kotlin
where { UsersTable.name like    "%alice%" }   // name LIKE $1
where { UsersTable.name notLike "%admin%" }   // name NOT LIKE $1
```

### Membership

```kotlin
where { UsersTable.status inList    listOf("ACTIVE", "PENDING") }   // status IN ($1, $2)
where { UsersTable.status notInList listOf("BANNED", "DELETED") }   // status NOT IN ($1, $2)
```

An empty list in `inList` is automatically replaced by the tautology `1 = 0`
(no rows) to avoid a SQL syntax error.

### Range

```kotlin
where { UsersTable.age.between(18, 65) }    // age BETWEEN $1 AND $2
```

### Null checks

```kotlin
where { UsersTable.deletedAt.isNull() }     // deleted_at IS NULL
where { UsersTable.deletedAt.isNotNull() }  // deleted_at IS NOT NULL
```

### Logical composition

```kotlin
// AND
where { (UsersTable.active eq true) and (UsersTable.age gte 18) }

// OR
where { (UsersTable.role eq "ADMIN") or (UsersTable.role eq "SUPERUSER") }

// NOT
where { not(UsersTable.banned eq true) }

// Complex combinations
where {
    ((UsersTable.role eq "ADMIN") or (UsersTable.role eq "MOD")) and
    UsersTable.active eq true
}
```

### Column-to-column comparison (JOIN ON clause)

```kotlin
OrdersTable.leftJoin(UsersTable) { OrdersTable.userId eq UsersTable.id }
```

## Sorting

```kotlin
fetchAll(UsersTable) {
    orderBy {
        UsersTable.createdAt.desc()  // newest first
        UsersTable.name.asc()        // then alphabetically
    }
}
```

## Pagination

Use `paginate` for application pages. It accepts `page` and `size` and returns
`Triple<List<E>, Long, Int>` as `(entities, count, totalPages)`. `page` is
1-based.

```kotlin
val (entities, count, totalPages) = aggo.paginate(
    UsersTable,
    page = 1,
    size = 20,
) {
    where { UsersTable.active eq true }
    orderBy { UsersTable.createdAt.desc() }
}
```

The typed-result overload returns database failures as values:

```kotlin
val result: Query<Triple<List<User>, Long, Int>, AggoError> =
    aggo.paginate(
        UsersTable,
        page = 1,
        size = 20,
        errorMap = constraintErrorMap(UsersTable),
    ) {
        where { UsersTable.active eq true }
        orderBy { UsersTable.createdAt.desc() }
    }
```

`limit` and `offset` remain low-level query primitives. Prefer `paginate` when
the caller needs total count and total pages.

```kotlin
fun getPage(page: Int, size: Int) = aggo.read {
    fetchAll(UsersTable) {
        where { UsersTable.active eq true }
        orderBy { UsersTable.createdAt.desc() }
        limit(size)
        offset(page * size)
    }
}
```

## Aggo vs Hibernate for reads

Hibernate reads often start from object navigation: load an entity, traverse a
relation, and let the ORM decide when SQL runs. Aggo reads are statement-first.
You decide exactly which query runs, whether it returns a list, a single row, a
stream, a projection, an aggregate row, or a page.

This avoids hidden N+1 queries and lazy proxy initialization errors. The trade
off is that you write the query shape explicitly.

## Consultas

Todas as leituras rodam dentro de `aggo.read { }` ou em helpers de conveniencia
como `aggo.fetchAll(...)`. O bloco recebe uma `Session` como receiver e a
conexao e liberada automaticamente ao final.

### Buscar varias entidades

```kotlin
val users: List<User> = aggo.read {
    fetchAll(UsersTable) {
        where { UsersTable.active eq true }
        orderBy { UsersTable.name.asc() }
    }
}
```

Quando voce passa um `ConstraintErrorMap`, o retorno muda para `Query`:

```kotlin
val result: Query<List<User>, AggoError> =
    aggo.fetchAll(UsersTable, constraintErrorMap(UsersTable)) {
        where { UsersTable.active eq true }
    }
```

Sem `errorMap`, erros do banco sao excecoes. Com `errorMap`, eles viram
`Query.Failure`.

### Buscar uma entidade

```kotlin
val user: User? = aggo.read {
    fetchOne(UsersTable) {
        where { UsersTable.email eq email }
    }
}
```

`fetchOne` sempre envia `LIMIT 1`, mesmo que a query base tenha outro limite.

### Paginacao de alto nivel

Use `paginate` para telas e APIs HTTP. Ela recebe `page` e `size` e retorna uma
tupla `(entities, count, totalPages)`:

```kotlin
val (entities, count, totalPages) = aggo.paginate(
    UsersTable,
    page = 1,
    size = 20,
) {
    where { UsersTable.active eq true }
    orderBy { UsersTable.createdAt.desc() }
}
```

O `count` usa o mesmo filtro da query e ignora `orderBy`, `limit` e `offset`.
`totalPages` e calculado com base em `count / size`.

### Operadores WHERE

```kotlin
where { UsersTable.active eq true }
where { UsersTable.age gte 18 }
where { UsersTable.name like "%alice%" }
where { UsersTable.status inList listOf("ACTIVE", "PENDING") }
where { UsersTable.deletedAt.isNotNull() }
where { (UsersTable.active eq true) and (UsersTable.age gte 18) }
```

### Streams

Use `stream` quando o resultado pode ser grande:

```kotlin
aggo.read {
    stream(ReportsTable) {
        where { ReportsTable.year eq 2026 }
    }.collect { report ->
        export(report)
    }
}
```

### Comparacao com Hibernate

No Hibernate, uma leitura pode disparar SQL de forma indireta ao acessar uma
relacao lazy. Em Aggo, a query e sempre explicita. Isso reduz surpresas, evita
N+1 escondido e combina melhor com servicos que querem SQL previsivel.
