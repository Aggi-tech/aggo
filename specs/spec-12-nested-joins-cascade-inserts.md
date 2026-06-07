# spec-12 - Nested Reads, Nested SELECTs, and Cascade Inserts

> Status: **proposed**.
> Parent: [spec-0-overview.md](spec-0-overview.md).
> Tracking issue: **#11**.
> Target version: **TBD**.
> Level: **L4 - high complexity / high API risk**.

## 1. Problem

Aggo already supports explicit two-table `LEFT JOIN` reads, basic single-table
`SELECT` queries, projection selects, aggregate selects, and transactional
multi-statement writes, but it does not yet model three common aggregate/query
patterns:

1. Reading a root entity together with referenced entities and materializing a
   nested Kotlin shape.
2. Using nested SQL selects/subqueries as part of a larger query.
3. Inserting a root entity together with dependent rows that reference it.

Today users can write the join and cascade-insert workflows manually:

```kotlin
val rows = aggo.session.fetchAllJoined(
    Orders.leftJoin(Customers) { Orders.customerId eq Customers.id }
)

aggo.tx { tx ->
    val orderId = tx.insertReturning(Orders, Orders.id) { ... }
    tx.insert(OrderLines) { OrderLines.orderId setTo orderId }
}
```

That is explicit and safe, but each application has to repeat grouping,
null-handling, dependency ordering, generated-key passing, and rollback
expectations.

## 2. Current implementation inventory

### 2.1 JOIN support already present

- `JoinSelect<L, R>` models a binary join between one left table and one right
  table.
- `Table<L>.leftJoin(Table<R>) { ... }` builds `LEFT JOIN` only.
- `JoinSelect.where`, `orderBy`, `limit`, and `offset` already refine the join
  AST immutably.
- `renderJoinSelect` renders qualified columns from both tables in positional
  mapping order.
- `Session.fetchAllJoined` and `Session.streamJoined` execute joins.
- Runtime mapping returns `JoinedRow<L, R>` with nullable `right`.
- `Session.mapJoinedRow` already uses `PositionalRow` so duplicate column names
  can be decoded by table-local `fromRow` lambdas.
- Right-side null detection uses the right table primary key columns first, then
  all right-side columns when no primary key exists.

### 2.2 JOIN support not present

- No nested entity materializer exists.
- No one-to-many grouping exists.
- No multi-join AST exists.
- No alias model exists for joining the same table more than once.
- No API currently maps `JoinedRow<L, R>` into a user-provided aggregate shape.
- No compile-time generated DTO projection mapper exists.
- No renderer support exists for column aliases such as `order__id` /
  `customer__id`; current implementation relies on positional row views.

### 2.3 SELECT/subquery support already present

- `Select<E>` models a single-table entity select.
- `ProjectionSelect<E>` models a single-table partial-column select.
- `AggregateSelect<E>` models single-table aggregate queries with projections,
  `GROUP BY`, `HAVING`, ordering, limit, and offset.
- `Predicate` already models typed WHERE expressions.
- `Expr` / `NamedExpr` already model expression projections for aggregate
  results.

### 2.4 SELECT/subquery support not present

- No subquery operand exists.
- No `EXISTS (SELECT ...)` predicate exists.
- No `IN (SELECT ...)` predicate exists.
- No scalar subselect expression exists.
- No derived table / `FROM (SELECT ...) AS alias` support exists.
- No correlated subquery alias binding exists.
- No renderer support exists for nested `SELECT` SQL.
- No result mapper exists for nested projection rows beyond user code reading
  `ProjectedRow`.

### 2.5 INSERT support already present

- `Insert<E>` represents a single-table insert with explicit assignments.
- `insert(table, entity)` walks `table.writableColumns` and skips generated
  columns.
- `insert(table) { column setTo value }` supports partial inserts.
- `renderInsert` binds values through the existing `RenderContext` and codecs.
- `Session.insert` executes one insert and returns affected rows.
- `Session.insertReturning` can return a generated primary key using the
  dialect's existing return strategy.
- Transaction boundaries already give atomicity for manually composed
  multi-table inserts.
- Foreign keys are schema metadata and migration inputs through the existing
  `Table.references` / `ForeignKey` model.

### 2.6 INSERT support not present

- No cascade insert AST exists.
- No graph/aggregate descriptor exists.
- No dependency-order planner exists for parent-before-child inserts.
- No generated-key propagation exists between insert steps except manual local
  variables in `aggo.tx`.
- No collection-valued child insert helper exists.
- No cycle detection exists for cascade graphs.
- No partial failure policy exists beyond the transaction rollback already
  provided by `aggo.tx`.

## 3. Level

This is **L4** work.

The SQL for the first usable version is not hard; the risk is API shape. The
feature crosses `schema`, `dsl`, `query`, `render`, and `runtime`, and a poor
design can accidentally recreate ORM behavior that Aggo intentionally avoids.

Reasons for L4:

- Requires new public API and likely new query AST shapes.
- Touches row materialization, which is a core zero-reflection boundary.
- Nested SELECTs require new predicate/expression rendering rules and alias
  scoping.
- Cascade insert needs deterministic statement ordering and generated-key
  propagation.
- One-to-many nested reads need grouping semantics, not only row decoding.
- Must avoid hidden lazy loading, entity scanning, and annotation-like mapping.

## 4. Goals

1. Let users declare aggregate read/write behavior explicitly with lambdas.
2. Preserve the zero-reflection law: no runtime property scanning, no
   annotations, no `KClass`, no proxies.
3. Reuse `Table`, `Column`, `ForeignKey`, `Select`, `ProjectionSelect`,
   `AggregateSelect`, `JoinSelect`, `Insert`, codecs, and session/transaction
   builders.
4. Keep query ASTs immutable data.
5. Keep rendering isolated in `render`.
6. Keep execution isolated in `runtime`.
7. Make every nested mapper explicit: either a user-supplied function for the
   low-level API or KSP-generated code for DTO projection APIs.
8. Make every cascade edge explicit.
9. Require cascade inserts to run inside `aggo.tx`.
10. Keep manual composition with existing APIs as the baseline behavior.
11. Support nested SELECTs only as explicit AST nodes, never as raw SQL strings.
12. Use KSP for DTO/nested-read mapping so applications do not hand-write row
    to DTO mapping code.

## 5. Non-goals

- No ORM identity map.
- No lazy loading.
- No dirty tracking.
- No entity proxies.
- No annotation scanning.
- No automatic discovery of relationships from Kotlin fields.
- No implicit cascade based only on database foreign keys.
- No hidden extra queries while reading properties.
- No many-to-many abstraction in the first implementation.
- No delete cascade or update cascade in this spec.
- No automatic batch insert requirement in the first implementation.
- No arbitrary raw SQL subquery escape hatch.
- No implicit correlated subquery generated from foreign keys alone.
- No runtime annotation scanning. KSP may inspect source symbols at compile
  time, but generated code must use normal Aggo APIs.

## 6. Proposed model

### 6.1 Nested JOIN reads

The first version should build on the existing binary join and expose an
explicit mapper:

```kotlin
val rows: List<OrderWithCustomer> = aggo.session.fetchJoined(
    Orders.leftJoin(Customers) { Orders.customerId eq Customers.id },
    map = { order, customer ->
        OrderWithCustomer(order = order, customer = customer)
    },
)
```

This is a small API over `fetchAllJoined`. It does not require new SQL rendering
and gives users a named aggregate shape without pretending Aggo owns the domain
object graph.

For one-to-many reads, add an explicit grouping helper over joined rows:

```kotlin
val orders: List<OrderWithLines> = aggo.session.fetchJoinedGrouped(
    Orders.leftJoin(OrderLines) { Orders.id eq OrderLines.orderId },
    key = { order -> order.id },
    mapRoot = { order -> OrderWithLines(order = order, lines = mutableListOf()) },
    addChild = { aggregate, line -> if (line != null) aggregate.lines += line },
)
```

This keeps grouping policy visible and avoids hidden collection semantics.

### 6.2 KSP-generated DTO mapping

Manual DTO mapping should not be the final user-facing model for nested reads.
The preferred implementation path is a KSP processor that generates projection
descriptors and mappers at compile time.

The generated code must:

- build on existing `Table` and `Column` descriptors;
- emit explicit projection columns with stable SQL aliases;
- generate row-to-DTO mappers that read by alias or generated column key;
- generate nested DTO construction code directly;
- avoid runtime reflection, `KClass`, annotation scanning, proxies, or entity
  field inspection;
- leave SQL rendering and execution in the existing `render` and `runtime`
  layers.

Example target usage:

```kotlin
@AggoDtoProjection
data class OrderSummaryDto(
    val orderId: Int,
    val customer: CustomerSummaryDto?,
)

data class CustomerSummaryDto(
    val id: Int,
    val name: String,
)

val rows: List<OrderSummaryDto> = aggo.session.fetchProjection(OrderSummaryProjection) {
    where { Orders.status eq "PAID" }
}
```

KSP would generate `OrderSummaryProjection` with:

- a query/projection shape based on existing joins or projection selects;
- aliases such as `order_id`, `customer__id`, and `customer__name`;
- a mapper equivalent to hand-written code, but generated at compile time.

Generated mapper shape:

```kotlin
internal fun mapOrderSummary(row: AliasedRow): OrderSummaryDto =
    OrderSummaryDto(
        orderId = row.required("order_id", IntCodec),
        customer = if (row.isNull("customer__id")) {
            null
        } else {
            CustomerSummaryDto(
                id = row.required("customer__id", IntCodec),
                name = row.required("customer__name", StringCodec),
            )
        },
    )
```

The exact annotation/API can change, but the invariant is fixed: aliases and
DTO mappers are generated by KSP, not discovered at runtime and not written by
application code for every DTO.

### 6.3 Future multi-join reads

Multi-join should be a separate follow-up after the binary API is stable.

Possible shape:

```kotlin
Orders
    .leftJoin(Customers) { Orders.customerId eq Customers.id }
    .leftJoin(OrderLines) { Orders.id eq OrderLines.orderId }
```

This requires a new AST because current `JoinSelect<L, R>` stores one
`JoinClause<R>` only. It also requires a row-view strategy that can address each
joined table deterministically, especially when the same table appears twice.

## 7. Proposed nested SELECT model

Nested SELECTs should be introduced in small, typed steps. The first target is
subqueries inside predicates because they reuse the current `WHERE` rendering
path and do not require new row materialization.

### 7.1 `EXISTS`

```kotlin
val customersWithOrders = select(Customers) {
    where {
        exists(
            select(Orders) {
                where { Orders.customerId eq Customers.id }
            }
        )
    }
}
```

This requires a new `Predicate.Exists` shape that contains a nested select-like
query. Correlation must be explicit through normal column predicates; Aggo must
not infer it from foreign keys.

### 7.2 `IN (SELECT ...)`

```kotlin
val customersWithPaidOrders = select(Customers) {
    where {
        Customers.id inSubquery selectProjection(Orders, Orders.customerId) {
            where { Orders.status eq "PAID" }
        }
    }
}
```

The subquery must project exactly one column, and its column type must match the
left operand type.

### 7.3 Scalar subselects

Scalar subselects are a later step because they affect expression typing:

```kotlin
val orderCount = count(Orders.id) `as` "order_count"

val q = select(Customers) {
    where {
        scalar(
            aggregate(Orders) {
                project(orderCount)
                where { Orders.customerId eq Customers.id }
            },
            orderCount,
        ) gt 0L
    }
}
```

This likely needs a first-class `SubqueryExpr<V>` and should wait until the
predicate subquery model is stable.

### 7.4 Derived tables

`FROM (SELECT ...) AS alias` is out of the first implementation. It requires an
aliasable row shape that is not currently represented by `Table<E>` or
`ProjectionSelect<E>`.

## 8. Proposed cascade insert model

The first version should be an execution helper, not a renderer feature. It can
compose existing `Insert` values inside `TransactionScope`.

```kotlin
val orderId = aggo.tx.insertCascade(Orders, Orders.id) {
    root {
        Orders.customerId setTo customerId
        Orders.status setTo "OPEN"
    }

    children(OrderLines) { generatedOrderId ->
        lines.map { line ->
            insert(OrderLines) {
                OrderLines.orderId setTo generatedOrderId
                OrderLines.sku setTo line.sku
                OrderLines.quantity setTo line.quantity
            }
        }
    }
}
```

Initial semantics:

- Execute root insert first.
- Return or capture the root primary key through existing `insertReturning`.
- Build child inserts from explicit lambdas.
- Execute child inserts in declaration order.
- Rely on the surrounding transaction for rollback.
- Do not infer children from fields on the root entity.

The helper may live in `dsl` as a builder that produces a runtime-executable
plan, or in `runtime` as a transaction helper. If it produces data, the data
shape should be a new immutable `CascadeInsert<E, PK>` query-like value.

## 9. Architecture rules

### schema

Allowed:

- Continue to declare `Table`, `Column`, and `ForeignKey` metadata.
- Add optional metadata only if it remains pure and immutable.

Forbidden:

- No cascade execution methods on `Table`.
- No nested object construction inside schema metadata.

### dsl

Allowed:

- Build nested-read, nested-select, and cascade-insert AST/plans from explicit
  lambdas.
- Reuse existing `WhereScope`, `InsertBuilder`, and column operators.
- Expose KSP-generated projection descriptors as normal DSL/query values.

Forbidden:

- No connection access.
- No SQL rendering.
- No reflection or field discovery.

### query

Allowed:

- Add immutable data shapes for grouped joins, subqueries, or cascade insert
  plans if needed.
- Add immutable aliased projection shapes if KSP-generated DTO mapping needs
  stable column labels.

Forbidden:

- No execution callbacks that touch runtime.

### render

Allowed:

- Reuse `renderJoinSelect` and `renderInsert` for the first implementation.
- Render generated column aliases when an explicit aliased projection shape is
  present.
- Add nested select rendering through `PredicateRenderer` / select renderers
  when explicit subquery AST nodes exist.
- Add multi-join rendering only when the AST exists.

Forbidden:

- No row mapping.
- No execution.

### runtime

Allowed:

- Execute composed join/insert plans.
- Apply grouping functions supplied by the user.
- Execute KSP-generated mappers against aliased rows or generated row views.
- Execute cascade steps inside `TransactionScope`.

Forbidden:

- No DDL generation.
- No schema mutation.
- No runtime symbol, annotation, constructor, or property inspection for DTOs.
- No hidden sessions or lazy loads.

## 10. Acceptance criteria

### NJ-ACC-1 - Binary nested read helper

`fetchJoined(query, map)` returns user-defined aggregate values by mapping each
existing `JoinedRow<L, R>` through an explicit lambda.

### NJ-ACC-2 - Left join null behavior is preserved

When the right table has no matching row, the mapper receives `null` for the
right entity.

### NJ-ACC-3 - Duplicate column names remain safe

Nested read helpers must preserve the current positional row mapping behavior so
tables with the same column names decode correctly.

### NJ-ACC-4 - Grouped one-to-many helper

`fetchJoinedGrouped` groups repeated root rows by an explicit key lambda and lets
the caller attach nullable children explicitly.

### NJ-ACC-5 - No hidden relationship discovery

No nested read API may inspect Kotlin properties, annotations, or runtime class
metadata.

### NJ-ACC-6 - KSP-generated DTO mapping

DTO projection APIs generate mapper code with KSP so users do not have to
manually map `ProjectedRow`, `JoinedRow`, or aliased row values into DTOs.

### NJ-ACC-7 - Stable generated aliases

KSP-generated projections must assign stable aliases to every selected field,
including nested fields, so duplicate column names are decoded deterministically.

### NJ-ACC-8 - Generated code uses existing structures

Generated mappers and projection descriptors must be ordinary Kotlin code that
uses existing Aggo `Table`, `Column`, codec, query, render, and runtime
contracts. They must not introduce a separate runtime mapping engine.

### NS-ACC-1 - EXISTS subquery predicate

The DSL can render `WHERE EXISTS (SELECT ...)` from an explicit nested select
AST.

### NS-ACC-2 - IN subquery predicate

The DSL can render `column IN (SELECT one_column ...)` and rejects subqueries
that do not project exactly one value.

### NS-ACC-3 - Correlation is explicit

Correlated subqueries must reference outer-table columns through normal column
predicates. Aggo must not infer correlation from foreign keys or entity fields.

### NS-ACC-4 - Nested SELECT parameter ordering is stable

Parameters from outer predicates and nested subqueries must be bound in rendered
SQL order through the existing `RenderContext`.

### NS-ACC-5 - No raw SQL subqueries

Nested SELECT APIs must be built from Aggo query AST values, not raw SQL string
fragments.

### CI-ACC-1 - Cascade insert root first

Cascade insert executes the root insert before any child insert.

### CI-ACC-2 - Generated key propagation

Cascade insert can pass a generated root primary key to child insert builders
using the existing `insertReturning` path.

### CI-ACC-3 - Transaction required

Cascade insert is only available from transaction-capable APIs. It must not be
available from `SessionBuilder`.

### CI-ACC-4 - Rollback remains atomic

If any child insert fails, the root insert and all prior child inserts roll back
through the existing transaction mechanism.

### CI-ACC-5 - Explicit cascade edges

Every child table and FK assignment must be declared by the caller. Aggo must
not infer cascade edges from domain object fields.

### CI-ACC-6 - Existing insert rendering is reused

The first implementation should execute normal `Insert` values and use
`renderInsert`; it should not introduce a separate SQL dialect path for single
row child inserts.

## 11. Required tests

- `RendererTest`: confirm no SQL output changes for existing `leftJoin` and
  `insert` rendering.
- `RendererTest`: add coverage only if a new multi-join renderer or cascade AST
  renderer is introduced.
- `RendererTest`: verify KSP-generated projection aliases render deterministically
  for root and nested DTO fields.
- `RendererTest`: render `EXISTS (SELECT ...)` with outer and inner parameters
  in stable bind order.
- `RendererTest`: render `IN (SELECT one_column ...)` and reject invalid
  multi-column subqueries.
- `IntegrationTest` or equivalent: reproduce nested binary read with duplicate
  column names and nullable right side.
- KSP compile test: generated DTO mapper constructs nested DTOs from aliased
  columns without reflection or manual mapping code.
- `IntegrationTest` or equivalent: execute a correlated `EXISTS` subquery.
- `IntegrationTest` or equivalent: cascade insert inserts root and children in
  one transaction.
- `IntegrationTest` or equivalent: child failure rolls back the root row.
- Builder/API test: cascade insert helpers are not available from read-only
  session APIs.

## 12. Suggested implementation order

1. Add `fetchJoined(query, map)` as a runtime convenience over
   `fetchAllJoined`.
2. Add `fetchJoinedGrouped` as a runtime grouping helper over
   `fetchAllJoined`.
3. Add an aliased projection row shape that KSP-generated mappers can read.
4. Add KSP generation for DTO projection descriptors and aliases.
5. Add `EXISTS` subquery predicates over existing `Select` values.
6. Add `IN (SELECT ...)` subquery predicates over single-column
   `ProjectionSelect` values.
7. Add cascade insert helper that composes existing `insertReturning` and
   `insert`.
8. Add tests for rollback and generated-key propagation.
9. Evaluate whether a first-class `CascadeInsert` immutable plan is needed.
10. Only then design scalar subselects, multi-join AST, derived tables, and
   aliasing.

## 13. Open questions

1. Should grouped one-to-many helpers require mutable accumulators from the
   caller, or return immutable values through a finalizer lambda?
2. Should cascade insert support application-provided primary keys without
   `insertReturning` in the first version?
3. Should cascade child steps return row counts, generated child keys, or only
   the root key/result?
4. Should `ForeignKey` metadata be optionally referenced for validation, while
   still requiring explicit FK assignment lambdas?
5. How should same-table joins be represented before an alias model exists?
6. Should `EXISTS` accept only `Select<E>` or also `ProjectionSelect<E>` and
   `AggregateSelect<E>`?
7. Should scalar subselects be supported before derived tables, or should both
   wait for a broader expression/alias redesign?
8. Should KSP mapping use annotations on DTO classes, a generated projection DSL,
   or both?
9. How should users disambiguate two fields with the same leaf name in different
   nested DTO branches?
