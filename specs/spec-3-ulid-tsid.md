# spec-3 — In-house ULID / TSID generator

> Status: **implemented**.
> Parent: [spec-0-overview.md](spec-0-overview.md).

## 1. Why in-house

`Checks.tsid()` already validated the format, but nothing in Aggo generated one. Consumers reached for third-party libs (`io.github.fxlae:ulid`, `io.hypersistence:tsid-creator`) that pulled tens of transitive classes for an ID-shaped string. A 200-LOC in-house implementation removes that pressure on the dep tree and lets us guarantee both monotonicity and constant-time generation under high concurrency.

## 2. Surface

### `com.aggitech.aggo.schema.ids.Ulid`

```kotlin
@JvmInline value class Ulid : Comparable<Ulid> {
    val value: String                    // 26-char Crockford base-32
    override fun compareTo(other: Ulid): Int
    override fun toString(): String

    companion object {
        fun generate(): Ulid             // monotonic, thread-safe
        fun parse(s: String): Ulid       // accepts lower-case, normalizes to upper
    }
}
```

Layout: 48 bits ms-since-epoch | 80 bits randomness. Encoded big-endian as 26 Crockford chars — string order matches numeric order, safe as a primary key.

### `com.aggitech.aggo.schema.ids.Tsid`

Same shape, 64-bit (42-bit timestamp | 22-bit randomness), 13 Crockford chars. Used for legacy schemas that already adopted `Checks.tsid()`.

### Codecs (in `schema/Codec.kt`)

```kotlin
val UlidCodec: Codec<Ulid> = ValueClassCodec(StringCodec, Ulid::parse, Ulid::value)
val TsidCodec: Codec<Tsid> = ValueClassCodec(StringCodec, Tsid::parse, Tsid::value)
```

### Check helper

```kotlin
Checks.ulid()  // → char_length("col") = 26 AND "col" ~ '^[0-9A-HJKMNP-TV-Z]{26}$'
Checks.tsid()  // → ... 13-char variant
```

## 3. Crockford base-32 encoder

Internal object, ~30 LOC, big-endian, alphabet `0-9 A-H J K M N P-T V-Z` (no `I L O U`). Width-checked at encode time to catch off-by-one errors during development. File: `schema/ids/Crockford.kt`.

## 4. Monotonicity contract

The ULID spec mandates strict monotonicity within the same millisecond. A naive `SecureRandom.nextBytes(10)` may go *backwards*, breaking lexicographic ordering as a primary key. Aggo's generator:

1. Caches the last `(timestamp, randomness)` pair under a single `@Synchronized` method.
2. If the new timestamp falls in the same ms as the last, increments the 80-bit randomness by 1.
3. If the randomness overflows (every byte was `0xFF`), advances the timestamp by 1 ms and re-seeds randomness.

`SecureRandom` is used as the entropy source — preferred over `ThreadLocalRandom` because IDs end up in logs, audit trails, and PKs that may leak through error responses.

The TSID generator follows the same pattern with the smaller 22-bit randomness field.

## 5. Tests (`UlidTest`)

| Case | What it asserts |
|------|-----------------|
| `ULID is always 26 chars Crockford base32` | Alphabet + length over 1 000 generations. |
| `ULIDs … strictly increasing` | 10 000 IDs, no duplicates, monotonic. |
| `ULID is sortable` | `ids == ids.sorted()` for a 1 000-batch. |
| `parse round-trips` | Including case-insensitive input. |
| `parse rejects I/L/O/U` | And wrong-length input. |
| `thread-safe under high concurrency` | 5 000 IDs across many coroutines, all unique. |
| `TSID is 13 chars and round-trips` | |
| `TSIDs strictly monotonic` | 5 000 in a tight loop, no duplicates. |

All 8 cases green in the local run (`mvn test`).

## 6. Files added / changed

| File | Status |
|------|--------|
| `schema/ids/Crockford.kt` | new |
| `schema/ids/Ulid.kt` | new — `Ulid`, `Tsid`, `MonotonicUlid`, `MonotonicTsid`. |
| `schema/Codec.kt` | adds `UlidCodec`, `TsidCodec`. |
| `schema/Checks.kt` | adds `Checks.ulid()`. |
| `src/test/.../UlidTest.kt` | new. |
