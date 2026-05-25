# spec-1 — Security hardening (V-1..V-10)

> Status: **implemented except V-1** (V-1 requires Maven Central artifact verification).
> Parent: [spec-0-overview.md](spec-0-overview.md).

Severity legend: **C**ritical / **H**igh / **M**edium / **L**ow / **I**nfo.

## V-1 [Critical] SCRAM SASL timing attack (transitive)

`com.ongres.scram:common:2.1` (pulled by `org.postgresql:r2dbc-postgresql:1.0.7.RELEASE`) compares client proofs and server signatures with `java.util.Arrays.equals`, which short-circuits on the first non-matching byte. A remote attacker observing latency between SASL `client-final-message` and `server-final-message` can recover authentication material byte by byte. Fix replaces `Arrays.equals` with `MessageDigest.isEqual` (constant time).

**Affected chain** (from `mvn dependency:tree`):

```
org.postgresql:r2dbc-postgresql:1.0.7.RELEASE
└── com.ongres.scram:client:2.1
    └── com.ongres.scram:common:2.1                ← vulnerable
        └── com.ongres.stringprep:saslprep:1.1
            └── com.ongres.stringprep:stringprep:1.1
```

**Mitigation path (pending artifact verification):**

1. Confirm on Maven Central the fixed `com.ongres.scram:client` artifact (the project changed groupId/artifactId conventions across the 2.x → 3.x boundary — check both `com.ongres.scram:client` and `com.ongres.scram:scram-client`).
2. Uncomment in `pom.xml` (already prepared as commented-out blocks):
   ```xml
   <properties>
     <scram.version>3.1</scram.version>     <!-- adjust to confirmed version -->
   </properties>
   <dependencyManagement>
     <dependencies>
       <dependency>
         <groupId>com.ongres.scram</groupId>
         <artifactId>client</artifactId>
         <version>${scram.version}</version>
       </dependency>
       <dependency>
         <groupId>com.ongres.scram</groupId>
         <artifactId>common</artifactId>
         <version>${scram.version}</version>
       </dependency>
     </dependencies>
   </dependencyManagement>
   ```
3. Run `IntegrationTest` against `postgres:16-alpine` (which forces SCRAM-SHA-256). The connection establishing successfully proves the SCRAM 3.x line is API-compatible.
4. Verify with `mvn dependency:tree | grep ongres` — must show **no** `2.x` line.

**Why this wasn't applied in the implementation PR:** the build environment in this workspace proxies Maven through a private GitHub Packages mirror that returned `401 Unauthorized` for `com.ongres.scram:client:3.1`. The user must apply this from an environment with Central access.

**Acceptance criteria:** `mvn dependency:tree` shows no `2.x` SCRAM, and the integration suite passes against `postgres:16-alpine`.

---

## V-2 [High] Identifier validation deferred to render time — *FIXED*

`Table.column(name, …)` previously accepted any `String` and stored it. Validation only ran at render time inside `PostgresDialect.quoteIdentifier()`. A `Table` constructed with `"first name"`, `"a; DROP …"`, or a UTF-8 homoglyph failed only on first query.

**Fix:**

- `requireValidIdentifier(name)` is now public API of `com.aggitech.aggo.dialect` (was `internal`).
- `Table.<init>` calls it on the table name.
- `Table.column(...)` calls it on every column name and rejects duplicates within the same table.

**Files changed:** `dialect/SqlDialect.kt`, `schema/Table.kt`.

**Test:** `SchemaValidationTest`.

---

## V-3 [High] `executeRaw` / `rawConnection` bypass every safety net — *FIXED*

Both were documented as "advanced callers only" but nothing in the type system enforced opt-in.

**Fix:** new `@AggoUnsafe` annotation (`@RequiresOptIn(level = ERROR)`) marks `Session.executeRaw` and `Session.rawConnection`. Call sites must add `@OptIn(AggoUnsafe::class)` or propagate the requirement.

**Files changed:** `runtime/Unsafe.kt` (new), `runtime/Session.kt`.

**Deviation from earlier draft:** the draft proposed a separate `UnsafeSession` returned by `session.unsafe()`. We landed on a simpler annotation-only gate — same safety property, smaller diff, no extra type to import.

**Test:** existing `IntegrationTest` annotated `@OptIn(AggoUnsafe::class)` — compile-fail without the annotation is the test.

---

## V-4 [Medium] `tx()` rollback may run on a connection already committed — *FIXED*

If the block threw *after* `commitTransaction()` succeeded, the catch path still called `rollbackTransaction()`, which Postgres rejects with a “no transaction in progress” error that was then silently added as a suppressed exception, polluting observability.

**Fix:** `var committed = false` is flipped to `true` after `commit().awaitFirstOrNull()` returns. The catch block skips rollback when `committed` is true.

**Files changed:** `runtime/Aggo.kt`.

**Test:** covered by manual code review; an automated test requires a faked R2DBC `Connection` that emits a post-commit error (contrived). Integration tests still validate the happy-path commit and rollback-on-throw.

---

## V-5 [Medium] Pool connection leaks on coroutine cancellation — *FIXED*

`finally { runCatching { pool.release(conn) } }` ran in cancellation-sensitive context. If the surrounding coroutine was cancelled at the wrong instant, the cancellation propagated through `release` and the connection leaked.

**Fix:** `withContext(NonCancellable) { runCatching { pool.release(conn) } }` for both `read` and `tx`.

**Files changed:** `runtime/Aggo.kt`.

---

## V-6 [Medium] Substring-based redaction misses real secrets and hides non-secrets — *FIXED*

The old `QueryLog.redact()` looked for `password|pwd|secret|token|api_key` substrings in the *value*. A value of `"hunter2"` in a `password` column was not redacted; a help-text row containing the word `"password"` was.

**Fix:** redaction is now column-driven.

- `Column.sensitive: Boolean = false` is the schema-level flag.
- `Bound` carries an optional `column: Column<*, *>?` set whenever the bind is attributable (assignments + `column op literal` predicates).
- `RenderContext.bind(value, codec, column)` threads the column through; `bindAssignment` and `PredicateRenderer.renderOperand` pass the originating column.
- `QueryLog.redact` masks when `bound.column?.sensitive == true`.

**Files changed:** `schema/Column.kt`, `render/RenderContext.kt`, `render/Renderers.kt`, `render/PredicateRenderer.kt`, `runtime/Logging.kt`.

**Test:** `LoggingRedactionTest`.

---

## V-7 [Low] `PostgresConfig` IPv6 zone-id support

Cosmetic. Documented; not patched.

---

## V-8 [Low] `Predicate.In` has no upper bound — *FIXED*

`Predicate.In` accepted any `Collection`. Postgres caps statements at 65535 parameters; a 70k-element list failed with an opaque driver error.

**Fix:** `Predicate.In.<init>` validates `values.size <= MAX_IN_SIZE` (default `32_000`, conservative).

**Files changed:** `query/Predicate.kt`.

**Test:** `PredicateLimitTest`.

---

## V-9 [Low] `slf4j-simple` test-scope hygiene

Currently `<scope>test</scope>` — fine; no change. Documented to prevent regression.

---

## V-10 [Info] Pool prepared-statement cache config — *FIXED* (moved to [spec-5](spec-5-performance.md))

Tracked under P-4 in spec-5.
