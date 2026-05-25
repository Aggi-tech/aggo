# spec-4 — Dependency slim-down

> Status: **mostly implemented** (V-1 SCRAM pin pending Central artifact verification).
> Parent: [spec-0-overview.md](spec-0-overview.md).

## 1. Decision table

| Dependency | Scope | Decision | Reason |
|---|---|---|---|
| `org.jetbrains.kotlin:kotlin-stdlib-jdk8` | compile | **Removed** — replaced with `kotlin-stdlib`. | `kotlin-stdlib-jdk7/-jdk8` were merged into `kotlin-stdlib` in Kotlin 1.8+. We are on 2.3.21. |
| `org.jetbrains.kotlin:kotlin-stdlib` | compile | **Added** | Single Kotlin runtime. |
| `io.r2dbc:r2dbc-spi` | compile | **Kept** | The interface we program against. |
| `io.r2dbc:r2dbc-pool` | compile | **Kept** | Production pool. CLAUDE.md forbids replacing it. |
| `org.postgresql:r2dbc-postgresql` | compile | **Kept** (bump pending) | Required driver. Either bump to a release that ships SCRAM ≥ 3.1, or pin SCRAM directly via `<dependencyManagement>` (V-1). |
| `com.ongres.scram:client` | transitive | **Pin to fixed version** (pending verification) | V-1 — see [spec-1](spec-1-security-hardening.md#v-1-critical-scram-sasl-timing-attack-transitive). |
| `com.ongres.scram:common` | transitive | **Pin to fixed version** (pending verification) | V-1. |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm` | compile | **Kept** (version via BOM) | Every public method that talks to R2DBC is `suspend`. |
| `org.jetbrains.kotlinx:kotlinx-coroutines-reactive` | compile | **Kept** (version via BOM) | `Publisher.asFlow()` + `awaitFirstOrNull()`. Replacing it in-house is 80 LOC of bridge code — not worth the maintenance. |
| `org.slf4j:slf4j-api` | compile | **Kept** | Logging facade only. No binding shipped. |
| `io.kotest:kotest-runner-junit5-jvm` | test | **Kept** | Test runner. |
| `io.kotest:kotest-assertions-core-jvm` | test | **Kept** | Assertion DSL. |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | test | **Kept** (version via BOM) | `runTest`. |
| `org.testcontainers:postgresql` | test | **Kept** | Integration tests. |
| `org.testcontainers:junit-jupiter` | test | **Kept** | Lifecycle binding. |
| `org.slf4j:slf4j-simple` | test | **Kept** | Test-only logger binding. |

## 2. BOMs

`kotlinx-coroutines-bom` (import scope) aligns `coroutines-core`, `coroutines-reactive`, `coroutines-test`, and the kotest-pulled `coroutines-debug` / `coroutines-jdk8` to the same version. Before this BOM, `mvn dependency:tree` showed kotest dragging `coroutines-debug:1.8.0` and `coroutines-jdk8:1.8.0` while the main classpath was on `1.10.2` — a silent classpath drift that has caused "works on my machine" reports.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.jetbrains.kotlinx</groupId>
      <artifactId>kotlinx-coroutines-bom</artifactId>
      <version>${coroutines.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

Direct dependencies on `coroutines-core-jvm`, `coroutines-reactive`, `coroutines-test` are now declared **without** an explicit `<version>` so the BOM is the single source of truth.

## 3. SCRAM pin (V-1) — applied at consumption time

```xml
<properties>
  <!-- Uncomment and set to the confirmed fixed release. -->
  <!-- <scram.version>3.1</scram.version> -->
</properties>

<dependencyManagement>
  <dependencies>
    <!-- Uncomment after confirming the artifact on Maven Central. -->
    <!--
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
    -->
  </dependencies>
</dependencyManagement>
```

**Why not applied in the implementation:** the workspace build environment proxies Maven through a private GitHub Packages mirror that returned `401 Unauthorized` for `com.ongres.scram:client:3.1`. The artifactId convention changed between the 2.x and 3.x lines (some 3.x releases use `scram-client` instead of `client`). The user must verify the exact GAV on Central before uncommenting.

## 4. What we are NOT removing (and why)

- **`r2dbc-pool`** — CLAUDE.md explicitly forbids replacing it. Writing an in-house pool re-introduces every race/leak the upstream lib already solved.
- **`kotlinx-coroutines-reactive`** — replacing the `Publisher`/`Flow` bridge in-house re-invents the reactive-streams plumbing for no benefit.
- **`slf4j-api`** — facade only; consumer chooses the binding. Removing it would ship our own logger interface, which is strictly worse for downstream apps.

## 5. Files changed

| File | Change |
|------|--------|
| `pom.xml` | version bump, `kotlin-stdlib-jdk8` → `kotlin-stdlib`, BOM import, SCRAM pin (commented). |
