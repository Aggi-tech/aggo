# spec-0 — aggo 0.2.0 overview & release plan

> Status: **implemented** (see individual spec files for per-feature status).
> Target version: **`0.1.0-SNAPSHOT` → `0.2.0-SNAPSHOT` → `0.2.0`**.
> Author/owner: Yuri Moinhos.
> Scope: `libs/aggo/` only.

## 1. Goals

1. **Fix CVE in the SCRAM transitive dependency** (timing side-channel on SASL auth).
2. **Eliminate the `session.update(update { … })` redundancy** with a receiver-style DSL that keeps the existing contracts intact.
3. **Provide a built-in ULID/TSID generator** so consumers stop pulling third-party ULID libs.
4. **Trim the dependency tree** — drop / pin anything that isn't carrying its weight.
5. **Performance** — shave per-call allocations on the hot path without breaking the “zero reflection” contract.

## 2. Non-goals

- No new SQL features (no UPSERT, no INNER/RIGHT join, no window functions).
- No swap of `io.r2dbc.pool.ConnectionPool` for an in-house pool.
- No JPMS module-info.
- No change to the wire format or `Codec`/`Column`/`Table` semantics.

## 3. Sub-specs

| # | File | Subject |
|---|------|---------|
| 1 | [spec-1-security-hardening.md](spec-1-security-hardening.md) | Vulnerability audit (V-1..V-10) + mitigations. |
| 2 | [spec-2-api-ergonomics.md](spec-2-api-ergonomics.md) | Receiver-style `tx`/`read`, builder-block overloads, one-shot `Aggo` helpers. |
| 3 | [spec-3-ulid-tsid.md](spec-3-ulid-tsid.md) | Built-in ULID/TSID generator + codecs + `Checks.ulid()`. |
| 4 | [spec-4-dependency-slim-down.md](spec-4-dependency-slim-down.md) | Dep audit, BOM, SCRAM pin path. |
| 5 | [spec-5-performance.md](spec-5-performance.md) | Pre-sized `StringBuilder`, `fetchOne` allocation cut, statement cache. |
| 6 | [spec-6-migrations.md](spec-6-migrations.md) | Aggo-owned migration generation, diffing, execution, and schema versioning. |

## 4. Semver decision

Current: `0.1.0-SNAPSHOT`. Proposed: **`0.2.0-SNAPSHOT` → release as `0.2.0`**.

- **MINOR**, not PATCH: adds API surface (builder-block overloads on `Session`, `Aggo.update/insert/delete`, `Ulid`, `Tsid`, `UlidCodec`, `TsidCodec`, `Checks.ulid()`, `Column.sensitive`).
- One narrow breaking change: `tx`/`read` lambdas become receiver-style. Pre-1.0, semver allows this; migration is one `sed` line (spec-2).
- Not MAJOR (1.0.0): the surface is still in flight (JOIN family, UPSERT, batch). Reserve 1.0 for when the API freezes.

`pom.xml` is bumped in this PR; release flow stays the same:

```bash
mvn versions:set -DnewVersion=0.2.0
git tag -a v0.2.0 -m "0.2.0"
mvn deploy
mvn versions:set -DnewVersion=0.3.0-SNAPSHOT
```

## 5. Migration checklist for consumers (`payment-ms`, `person-ms`)

1. Bump `aggo` to `0.2.0`.
2. Run the sed script in spec-2 (`tx { session -> … }` → `tx { … }`).
3. Add `@OptIn(AggoUnsafe::class)` to any file calling `session.executeRaw` or `session.rawConnection`.
4. Replace any third-party ULID/TSID import with `com.aggitech.aggo.schema.ids.Ulid` / `Tsid`.
5. After SCRAM pin is applied (spec-4 §V-1), run `mvn dependency:tree | grep ongres` — must show no `2.x` line.

## 6. Acceptance criteria

- [x] V-2, V-3, V-4, V-5, V-6, V-8 have a regressing test and a fix commit.
- [ ] V-1 SCRAM pin applied with confirmed fixed-version artifact (pending verification of available Central release; see spec-4 §V-1).
- [x] `aggo.tx { update(table) { … } }` compiles and runs.
- [x] `Ulid.generate()` produces 26-char Crockford strings, monotonic across the same ms, thread-safe.
- [x] `Checks.ulid()` accepts `Ulid.generate().value`.
- [x] `pom.xml` declares `<version>0.2.0-SNAPSHOT</version>`.
- [ ] `IntegrationTest` re-run against `postgres:16-alpine` after the SCRAM pin (forces SCRAM-SHA-256).
- [x] All 33 non-integration unit tests green.

## 7. Out of scope (tracked for 0.3.0+)

- INNER / RIGHT joins, 3-way joins.
- UPSERT (`INSERT … ON CONFLICT`).
- Batch insert (`Statement.add()`).
- Subqueries in `WHERE`.
- Typed result set for ad-hoc projections.
