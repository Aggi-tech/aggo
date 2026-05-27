# Aggo Agent Instructions (CODEX)

This file provides context and instructions for AI agents and Codex-powered tools working on the Aggo repository.

## Project Identity
Aggo is a reflection-free, type-safe R2DBC DSL for PostgreSQL in Kotlin, optimized for GraalVM Native Image.

## Core Rules for Agents
- **No Runtime Reflection**: Do not use `KClass`, `::class.java`, or reflection-based mapping in `schema/`, `query/`, `dsl/`, or `render/`.
- **Workflow Compliance**:
  - All changes require an Issue ID.
  - Branch pattern: `{feature|hotfix|ci}/{issue-id}`.
  - Commits must reference the Issue ID.
- **Architectural Flow**: `schema` -> `dsl` -> `query` (AST) -> `render` -> `runtime`. No reverse dependencies.

## Key Components
- **Codecs**: Bridge between Kotlin types and R2DBC. Use `ValueClassCodec` for inline value classes.
- **DSL**: Builder-based query construction.
- **Migrations**: Snapshot-based DDL generation in `migration/`.
- **Renderers**: Pure SQL generation from query AST.

## Documentation Index
- `llms.txt`: Full index of technical skills and docs.
- `CLAUDE.md`: Architecture map and engineering standards.
- `.claude/skills/`: Detailed component guides.

## Tooling
- Build: `./gradlew compileKotlin`
- Test: `./gradlew test` (Integration tests require Docker/Testcontainers)
- Migrations: `./gradlew aggoMigrate`
