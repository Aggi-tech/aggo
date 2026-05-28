# GitHub Copilot Instructions for Aggo

## Architecture & Logic
- **Reflection**: Aggo is reflection-free. NEVER suggest using `KClass`, `::class.java`, or `java.lang.reflect` in core packages (`schema`, `query`, `dsl`, `render`).
- **Value Classes**: Always use `ValueClassCodec` when mapping `@JvmInline value class` types to columns.
- **SQL Rendering**: Parameters must be bound using `ctx.bind(value, codec)`. Do not generate positional placeholders like `$1` manually.

## Workflow
- Always ask for an Issue ID before starting a new feature or fix.
- Suggest branch names in the format: `{feature|hotfix|ci}/{issue-id}`.
- Commit messages must be descriptive, reference the Issue ID, and be limited to 20 words.

## Code Style
- Use Kotlin idiomatic DSL patterns.
- Prefer `required { it.property }` and `optional { it.property }` in table definitions.
- Maintain the strict separation between schema metadata and execution runtime.

Refer to `llms.txt` for detailed technical guides.
