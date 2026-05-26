package com.aggitech.aggo.runtime

import io.r2dbc.spi.R2dbcException

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

typealias Transaction<S, E> = Query<S, E>

inline fun <S, E, R> Query<S, E>.map(transform: (S) -> R): Query<R, E> = when (this) {
    is Query.Success -> Query.Success(transform(value))
    is Query.Failure -> this
}

inline fun <S, E, R> Query<S, E>.flatMap(transform: (S) -> Query<R, E>): Query<R, E> = when (this) {
    is Query.Success -> transform(value)
    is Query.Failure -> this
}

inline fun <S, E, R> Query<S, E>.fold(onSuccess: (S) -> R, onFailure: (E) -> R): R = when (this) {
    is Query.Success -> onSuccess(value)
    is Query.Failure -> onFailure(error)
}

sealed interface AggoError {
    val cause: Throwable?
}

data class ConstraintError(
    val key: String,
    val constraintName: String,
    val kind: ConstraintKind,
    val table: String? = null,
    val column: String? = null,
    override val cause: Throwable? = null,
) : AggoError

data class DatabaseError(
    val message: String,
    val sqlState: String? = null,
    val errorCode: Int? = null,
    override val cause: Throwable? = null,
) : AggoError

data class TransactionError(
    val message: String,
    override val cause: Throwable? = null,
) : AggoError

enum class ConstraintKind {
    CHECK,
    UNIQUE,
    FOREIGN_KEY,
}

data class ConstraintErrorDescriptor(
    val key: String,
    val constraintName: String,
    val kind: ConstraintKind,
    val table: String,
    val column: String,
)

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

inline fun <reified T : Throwable> Throwable.findCause(): T? =
    generateSequence(this as Throwable?) { it.cause }.filterIsInstance<T>().firstOrNull()
