# Reactive Notifications QA Test Plan

This document explains how `specs/spec-10-reactive-notifications.md` is validated.
It is intended both as a QA reference and as onboarding material for contributors.

## Quality Objective

Reactive notifications must deliver committed domain events without leaking
connections, silently losing trigger definitions, truncating payloads, or exposing
different business APIs per database dialect.

The tests prioritize observable behavior over implementation details. Generated SQL
is tested semantically where no target database is available, and PostgreSQL behavior
is tested end-to-end with Testcontainers.

## Feature Flow

1. A `NotifyChannel<P>` defines a validated logical name and explicit payload codec.
2. A `NotifyTrigger<E>` associates table events with a channel and SQL payload expression.
3. `migrationSchema` and `migrationPlan` produce table, outbox, function, and trigger DDL.
4. PostgreSQL delivers notifications through `AggoListener`.
5. MySQL and Oracle write notifications to an outbox consumed by `OutboxListener`.
6. `Session.notify` emits an explicit PostgreSQL notification in the current transaction.

## Test Styles

### `BehaviorSpec`

Use `BehaviorSpec` for isolated business rules and contracts:

- `given`: initial domain or configuration state
- `when`: action or condition under evaluation
- `then`: externally observable result

Primary suite:

- `ReactiveNotificationsBehaviorTest`

### `FeatureSpec`

Use `FeatureSpec` for acceptance and integration scenarios:

- `feature`: user-visible capability
- `scenario`: independently executable acceptance criterion

Primary suite:

- `IntegrationTest`, feature `reactive PostgreSQL notifications`

Tests must not be disabled to hide an implementation defect. Infrastructure-dependent
tests may use `enabledIf` only when the required Docker runtime is unavailable.

## Acceptance Traceability

| Criterion | Expected behavior | Automated evidence |
|---|---|---|
| N-ACC-1 | Trigger notification arrives after commit | `IntegrationTest`: trigger notification after commit |
| N-ACC-2 | Rollback emits no notification | `IntegrationTest`: rollback scenario |
| N-ACC-3 | Outbox polling delivers MySQL/Oracle notifications | Pending executable backend test |
| N-ACC-4 | PostgreSQL listener reconnects after connection loss | Pending controlled connection-failure test |
| N-ACC-5 | Cancellation performs `UNLISTEN` and closes connection | Pending instrumented connection test |
| N-ACC-6 | Migration plan contains trigger DDL | `ReactiveNotificationsBehaviorTest`: PostgreSQL migration plan |
| N-ACC-7 | PostgreSQL function and trigger DDL are valid | Unit semantic checks plus PostgreSQL integration migration |
| N-ACC-8 | MySQL emits one trigger per event | `ReactiveNotificationsBehaviorTest`: multi-event MySQL DDL |
| N-ACC-9 | MySQL creates managed outbox | `ReactiveNotificationsBehaviorTest`: MySQL migration plan |
| N-ACC-10 | Invalid channel names fail at construction | `ReactiveNotificationsBehaviorTest`: invalid names |
| N-ACC-11 | Invalid/absent numeric payload decodes to null | `ReactiveNotificationsBehaviorTest`: codec boundaries |
| N-ACC-12 | Two collectors receive independently | `IntegrationTest`: concurrent collectors |
| N-ACC-13 | 8 kB payload is not truncated | Codec boundary plus PostgreSQL integration scenario |

Items marked pending represent quality gaps. They must not be interpreted as covered
by nearby unit tests.

## Risk-Based Coverage

### Critical

- Notifications emitted by rolled-back transactions.
- Trigger DDL that references `NEW` during DELETE or `OLD` during INSERT/UPDATE.
- A green CI integration stage that silently skips every test when Docker is unavailable.
- Migration snapshots losing trigger metadata between generations.

### High

- Listener connection leaks after cancellation.
- Reconnection stopping notification delivery.
- Outbox polling missing, duplicating, or reordering rows.
- Trigger or channel name injection.

### Medium

- Codec boundaries and malformed payloads.
- Backoff attempt counting and delay capping.
- Custom outbox table consistency.

## Running The Tests

Unit and contract tests:

```bash
mvn test -Dtest='!IntegrationTest'
```

PostgreSQL acceptance tests:

```bash
mvn test -Dtest='IntegrationTest'
```

The integration command requires Docker. A successful run must report executed tests;
zero executed tests or all scenarios skipped is not acceptance evidence.

## Failure Interpretation

- Compilation failure in production sources: release-blocking defect; tests cannot
  provide behavioral evidence until compilation is restored.
- BDD assertion failure: expected behavior from the spec is not implemented.
- Timeout waiting for a notification: investigate listener registration, transaction
  commit, trigger DDL, and channel qualification.
- Unexpected notification after rollback: critical transactional-semantics defect.
- Skipped integration scenarios: environment result, not a passing quality result.

## Current Known Findings

The QA suite intentionally asserts correct per-event row-image semantics:

- INSERT and UPDATE payloads must use `NEW`.
- DELETE payloads must use `OLD`.

A multi-event trigger cannot select one row image for every event. Fragment-only DDL
assertions can pass while the generated trigger is semantically wrong.

The current implementation also has compilation blockers in the notification API and
outbox listener. These are production defects and are not corrected by the QA suite.
