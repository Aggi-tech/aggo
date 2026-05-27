# Module aggo

Aggo is a reflection-free Kotlin DSL for R2DBC. It gives Kotlin services a
small, explicit database layer: schemas are declared as `Table<E>` objects,
queries are built with a typed DSL, SQL is rendered by dialects, and runtime
execution uses R2DBC without annotation scanning or runtime reflection.

Aggo follows a one-way architecture:

```text
schema -> dsl -> query -> render -> runtime
```

The generated Dokka site includes the API reference and the handwritten user
guides below. Each guide contains English and Portuguese sections.

## Guides / Guias

Dokka renders custom Markdown as module or package documentation. The guides
are attached to the package pages below:

| Guide | Dokka page |
|-------|------------|
| Getting Started / Primeiros Passos | Module page: `aggo` |
| Schema Definition / Definicao de Schema | Package page: `com.aggitech.aggo.schema` |
| Querying / Consultas | Package page: `com.aggitech.aggo.dsl` |
| Writing Data / Escritas | Package page: `com.aggitech.aggo.runtime` |
| JOIN Queries / Consultas JOIN | Package page: `com.aggitech.aggo.query` |
| Migration Generation / Migracoes | Package page: `com.aggitech.aggo.migration` |
| Multitenancy / Multitenancy | Package page: `com.aggitech.aggo.runtime.multitenancy` |

## When coming from Hibernate

Hibernate is an ORM. It tracks entities, uses annotations and reflection, and
can hide SQL behind a persistence context. Aggo is intentionally closer to SQL:
there is no lazy loading, no entity manager, no dirty checking, and no runtime
metadata scan. You write explicit table descriptors and explicit queries.

That trade-off is deliberate. Aggo favors predictable SQL, native-image
compatibility, and small runtime behavior over ORM automation. If you want
transparent object graphs and automatic persistence, Hibernate is a better fit.
If you want explicit database access with Kotlin types and no reflection, use
Aggo.

## Gerar localmente

```bash
mvn -q -DskipTests dokka:dokka
```

The generated site is written to `target/dokka`.
