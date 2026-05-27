# Module aggo

Aggo is a reflection-free Kotlin DSL on top of R2DBC. The public API follows a
one-way architecture:

```
schema -> dsl -> query -> render -> runtime
```

The generated API reference is built from KDoc with Dokka. It is intended for
GitHub Pages publishing and complements the handwritten guides in `/docs`.

## Main entry points

- `com.aggitech.aggo.runtime.Aggo` for single-database usage.
- `com.aggitech.aggo.runtime.Session` for query execution inside `read` and
  `tx` blocks.
- `com.aggitech.aggo.schema.Table` and `Column` for explicit schema mapping.
- `com.aggitech.aggo.migration` for migration snapshots and plans.
- `com.aggitech.aggo.runtime.multitenancy.MultiSchemaAggo` for
  schema-per-tenant isolation.
- `com.aggitech.aggo.runtime.multitenancy.MultiDatabaseAggo` for
  database-per-tenant isolation.

## Handwritten guides

- [Getting Started](01-getting-started.md)
- [Schema Definition](02-schema.md)
- [Querying](03-querying.md)
- [Writing Data](04-writes.md)
- [JOIN Queries](05-joins.md)
- [Migration Generation](06-migrations.md)
- [Multitenancy](07-multitenancy.md)
