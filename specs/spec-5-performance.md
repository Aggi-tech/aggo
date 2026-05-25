# spec-5 — Performance (P-1 / P-3 / P-4)

> Status: **implemented**.
> Parent: [spec-0-overview.md](spec-0-overview.md).

## 1. Changes

| # | File | Change | Expected impact |
|---|------|--------|-----------------|
| P-1 | `render/Renderers.kt` | Pre-size `StringBuilder` per renderer (`renderSelect`, `renderInsert`, `renderUpdate`, `renderDelete`) with a rough estimate (`64 + table.length + columns × 16 + …`). | Fewer char-array grow-and-copy passes on the hot path — measurable on SELECTs with > 5 columns. |
| P-2 | `render/RenderContext.kt` | `params` getter returns the live `MutableList` (read-only view). Already the case; documented to prevent regression. | Zero extra List allocations per render. |
| P-3 | `runtime/Session.kt`, `render/Renderers.kt` | `fetchOne` passes `limitOverride = 1` to `renderSelect` instead of allocating a `Select.copy(limit = 1)`. | One `Select` data-class allocation skipped per call. |
| P-4 | `runtime/Pool.kt` | New `PoolConfig.preparedStatementCacheQueries: Int = 256`. Forwarded to the r2dbc-postgresql `preparedStatementCacheQueries` option. | Driver-side statement reuse — Postgres parse + plan skipped on hot queries. |
| P-5 | `runtime/Binder.kt` | `bindOne` already pre-checks `bound.value == null` before any cast; no change. Verified to prevent regression. | None now; guards future drift. |

## 2. P-1 details

Each renderer now calls `buildString(estimate) { … }` instead of `buildString { … }`. The estimate is intentionally a coarse upper bound on a short statement — it's better to over-allocate by 30 % once than to suffer multiple `Arrays.copyOf` passes growing the internal array from 16 → 32 → 64 → 128 chars during a 250-char SQL build.

Numbers picked from inspection of typical Aggo statements:

- SELECT: `64 + columns.length + table.columns.size * 16` — covers prefix, table, ORDER BY, LIMIT/OFFSET.
- INSERT: `48 + table.length + cols.length + placeholders.length` — already deterministic.
- UPDATE / DELETE: `32 + table.length + setClause.length` — tight, with WHERE growing dynamically.

The estimates are *hints*; `StringBuilder` grows on its own if exceeded.

## 3. P-3 details

Before:

```kotlin
suspend fun <E> fetchOne(query: Select<E>): E? {
    val limited = if (query.limit == null) query.copy(limit = 1) else query
    return stream(limited).toList().firstOrNull()
}

private fun <E> Select<E>.copy(limit: Int? = this.limit): Select<E> =
    Select(table, where, orderBy, limit, offset)
```

After:

```kotlin
suspend fun <E> fetchOne(query: Select<E>): E? =
    flow {
        val rendered = renderSelect(query, dialect, limitOverride = 1)
        ...
    }.toList().firstOrNull()
```

`renderSelect` learned an optional `limitOverride: Int? = null` parameter — when non-null, it overrides whatever `query.limit` is. The user-visible `Select` is unchanged.

## 4. P-4 details

The r2dbc-postgresql driver supports a per-connection prepared-statement cache. Once a statement (identified by its exact SQL string) is seen N times, subsequent executions skip Postgres's parse + plan stages. Aggo's renderer is **already deterministic** — the same `Select<E>` always produces the same SQL — so the cache pays off immediately.

Wiring:

```kotlin
optionsBuilder.option(
    io.r2dbc.spi.Option.valueOf<Int>("preparedStatementCacheQueries"),
    config.pool.preparedStatementCacheQueries,
)
```

Default is `256`, matching the driver's own default. Consumers can opt out with `preparedStatementCacheQueries = 0` (useful for memory-constrained processes that rotate queries faster than the cache TTL).

## 5. Tests

P-1, P-3, P-4 are exercised implicitly by the existing `RendererTest` (output unchanged) and `IntegrationTest` (no semantic regression). The benchmarks are not part of the suite; if they become useful, they belong in a separate JMH module.

## 6. Files changed

| File | Change |
|------|--------|
| `render/Renderers.kt` | P-1, P-3 (`limitOverride` parameter). |
| `runtime/Session.kt` | P-3 (inlined `fetchOne` path). |
| `runtime/Pool.kt` | P-4 (`preparedStatementCacheQueries` config). |
