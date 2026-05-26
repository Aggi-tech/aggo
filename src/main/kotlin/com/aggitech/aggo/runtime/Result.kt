package com.aggitech.aggo.runtime

import io.r2dbc.spi.R2dbcException

/**
 * Typed result returned by exception-capturing query helpers.
 *
 * Use [map], [flatMap], and [fold] to compose database work without throwing
 * through application layers. The success type is the value returned by the
 * query block; the error type is normally [AggoError] or an application error
 * created from it.
 */
sealed interface Query<out S, out E> {
    data class Success<S>(val value: S) : Query<S, Nothing>
    data class Failure<E>(val error: E) : Query<Nothing, E>

    val isSuccess: Boolean get() = this is Success<S>
    val isFailure: Boolean get() = this is Failure<E>

    fun getOrNull(): S? = when (this) {
        is Success -> value
        is Failure -> null
    }

    fun errorOrNull(): E? = when (this) {
        is Success -> null
        is Failure -> error
    }
}

/**
 * Transactional typed result. Kept as a domain alias so call sites can express
 * intent even though composition semantics are identical to [Query].
 */
typealias Transaction<S, E> = Query<S, E>

/** Transform the success value while preserving the error unchanged. */
inline fun <S, E, R> Query<S, E>.map(transform: (S) -> R): Query<R, E> = when (this) {
    is Query.Success -> Query.Success(transform(value))
    is Query.Failure -> this
}

/** Chain a second typed-result operation that depends on the success value. */
inline fun <S, E, R> Query<S, E>.flatMap(transform: (S) -> Query<R, E>): Query<R, E> = when (this) {
    is Query.Success -> transform(value)
    is Query.Failure -> this
}

/** Collapse a typed result into a single value. */
inline fun <S, E, R> Query<S, E>.fold(onSuccess: (S) -> R, onFailure: (E) -> R): R = when (this) {
    is Query.Success -> onSuccess(value)
    is Query.Failure -> onFailure(error)
}

/** Base type for errors produced by Aggo's typed-result helpers. */
sealed interface AggoError {
    val cause: Throwable?
}

/**
 * A database constraint failure mapped to a stable application-facing [key].
 *
 * The key comes from schema metadata such as `check(key = ...)`,
 * `unique(key = ...)`, or `references(key = ...)`.
 */
data class ConstraintError(
    val key: String,
    val constraintName: String,
    val kind: ConstraintKind,
    val table: String? = null,
    val column: String? = null,
    override val cause: Throwable? = null,
) : AggoError

/** Fallback for database errors that are not mapped to a known constraint. */
data class DatabaseError(
    val message: String,
    val sqlState: String? = null,
    val errorCode: Int? = null,
    override val cause: Throwable? = null,
) : AggoError

/** Reserved for transaction lifecycle failures such as begin/commit/rollback errors. */
data class TransactionError(
    val message: String,
    override val cause: Throwable? = null,
) : AggoError

/** Constraint kinds Aggo can map from database errors. */
enum class ConstraintKind {
    CHECK,
    UNIQUE,
    FOREIGN_KEY,
}

/** Descriptor generated from schema metadata for one mappable constraint. */
data class ConstraintErrorDescriptor(
    val key: String,
    val constraintName: String,
    val kind: ConstraintKind,
    val table: String,
    val column: String,
)

/**
 * Lookup table that maps database constraint names to typed [ConstraintError]s.
 *
 * Build one with `constraintErrorMap(UsersTable, ProfilesTable)` and pass it to
 * `aggo.readQuery(errorMap) { ... }` or `aggo.transaction(errorMap) { ... }`.
 */
class ConstraintErrorMap private constructor(
    private val byName: Map<String, ConstraintErrorDescriptor>,
) {
    fun find(name: String?): ConstraintErrorDescriptor? =
        name?.let { byName[it] }

    fun map(t: Throwable): AggoError {
        val constraintName = extractConstraintName(t)
        val descriptor = find(constraintName)
        if (descriptor != null) {
            return ConstraintError(
                key = descriptor.key,
                constraintName = descriptor.constraintName,
                kind = descriptor.kind,
                table = descriptor.table,
                column = descriptor.column,
                cause = t,
            )
        }
        val r2dbc = t.findCause<R2dbcException>()
        return DatabaseError(
            message = t.message ?: t::class.qualifiedName.orEmpty(),
            sqlState = r2dbc?.sqlState,
            errorCode = r2dbc?.errorCode,
            cause = t,
        )
    }

    companion object {
        val empty = ConstraintErrorMap(emptyMap())

        fun of(descriptors: Iterable<ConstraintErrorDescriptor>): ConstraintErrorMap =
            ConstraintErrorMap(descriptors.associateBy { it.constraintName })
    }
}

/** Best-effort extraction for common PostgreSQL/R2DBC constraint messages. */
fun extractConstraintName(t: Throwable): String? {
    val message = generateSequence(t) { it.cause }
        .mapNotNull { it.message }
        .joinToString("\n")
    val quoted = Regex("""constraint ["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .find(message)
        ?.groupValues
        ?.getOrNull(1)
    if (quoted != null) return quoted
    return Regex("""constraint ([A-Za-z_][A-Za-z0-9_]*)""", RegexOption.IGNORE_CASE)
        .find(message)
        ?.groupValues
        ?.getOrNull(1)
}

/** Walks [Throwable.cause] looking for a specific cause type. */
inline fun <reified T : Throwable> Throwable.findCause(): T? =
    generateSequence(this as Throwable?) { it.cause }.filterIsInstance<T>().firstOrNull()
