---
name: aggo-predicate-operator
description: Guide for adding new Predicate shapes and DSL infix operators in Aggo — Predicate.kt, ComparisonOp, PredicateRenderer, and Operators.kt extension functions. Use when adding a new WHERE operator (e.g. SIMILAR TO, ANY, @>, jsonb operators) or a new predicate shape.
---

# Aggo Predicate & Operator Skill

You are adding a new WHERE operator or predicate shape to Aggo's query AST.

## How predicates work

```
DSL operator (Operators.kt)
    → Predicate node (query/Predicate.kt)
        → RenderContext in PredicateRenderer.kt
            → SQL string with $N parameters
```

## Step 1 — Add a ComparisonOp variant (if needed)

File: `src/main/kotlin/com/aggitech/aggo/query/Predicate.kt`

```kotlin
enum class ComparisonOp(val sql: String) {
    EQ("="), NEQ("<>"),
    GT(">"), GTE(">="), LT("<"), LTE("<="),
    LIKE("LIKE"), ILIKE("ILIKE"),
    // Add new variant:
    SIMILAR_TO("SIMILAR TO"),
}
```

Only add a new variant if none of the existing ones maps to the SQL operator you need.

## Step 2 — Add a Predicate shape (if needed)

For operators that don't fit `Predicate.Cmp(col, op, literal)`, define a new data class:

```kotlin
// Example: BETWEEN
data class Between<E, V>(
    val column: Operand.Col<E, V>,
    val low: Operand.Literal<V>,
    val high: Operand.Literal<V>,
) : Predicate
```

The `when` in `PredicateRenderer.render()` is exhaustive — the compiler will flag any missing branch.

## Step 3 — Handle the new shape in PredicateRenderer

File: `src/main/kotlin/com/aggitech/aggo/render/PredicateRenderer.kt`

Add a branch to the exhaustive `when`:

```kotlin
is Predicate.Between<*, *> -> {
    val col = renderOperand(predicate.column, ctx)
    val low = ctx.bind(predicate.low.value, predicate.low.codec)
    val high = ctx.bind(predicate.high.value, predicate.high.codec)
    "$col BETWEEN $low AND $high"
}
```

`ctx.bind(value, codec)` registers the parameter and returns the `$N` placeholder. **Never** build placeholder strings manually.

## Step 4 — Add the DSL operator in Operators.kt

File: `src/main/kotlin/com/aggitech/aggo/dsl/Operators.kt`

Extension functions on `Column<E, V>` that produce `Predicate` nodes:

```kotlin
// Simple comparison — reuse existing ComparisonOp
infix fun <E, V> Column<E, V>.similarTo(value: V?): Predicate =
    Predicate.Cmp(Operand.Col(this), ComparisonOp.SIMILAR_TO, Operand.Literal(value, codec))

// Custom predicate shape
infix fun <E, V : Comparable<V>> Column<E, V>.between(range: Pair<V, V>): Predicate =
    Predicate.Between(
        column = Operand.Col(this),
        low    = Operand.Literal(range.first, codec),
        high   = Operand.Literal(range.second, codec),
    )
```

Usage in queries:
```kotlin
where { UsersTable.name similarTo "Alice%" }
where { UsersTable.score between (50 to 100) }
```

## Predicate shapes already in the AST

| Shape | SQL produced |
|-------|-------------|
| `Predicate.Cmp(col, op, literal)` | `col op $N` |
| `Predicate.IsNull(col)` | `col IS NULL` |
| `Predicate.IsNotNull(col)` | `col IS NOT NULL` |
| `Predicate.And(predicates)` | `p1 AND p2 AND …` |
| `Predicate.Or(predicates)` | `p1 OR p2 OR …` |
| `Predicate.Not(predicate)` | `NOT (p)` |
| `Predicate.In(col, values)` | `col IN ($1, $2, …)` |
| `Predicate.NotIn(col, values)` | `col NOT IN ($1, $2, …)` |
| `Predicate.Between(col, low, high)` | `col BETWEEN $N AND $M` |
| `Predicate.ColCmp(left, op, right)` | `col1 op col2` (column-to-column) |

## Operand types

```kotlin
Operand.Col(column)           // references a table column
Operand.Literal(value, codec) // a bound parameter
```

Never put raw SQL strings into an `Operand`. All values must go through `ctx.bind()` via `Operand.Literal`.

## Testing

Add a case to `RendererTest`:
1. Build a query with the new operator.
2. Assert the SQL string contains the correct fragment.
3. Assert the parameter list contains the expected values in order.

```kotlin
@Test
fun `similarTo renders correctly`() {
    val q = select(UsersTable) { where { UsersTable.name similarTo "Alice%" } }
    val rendered = renderSelect(q, PostgresDialect)
    assertThat(rendered.sql).contains("SIMILAR TO $1")
    assertThat(rendered.params).hasSize(1)
}
```
