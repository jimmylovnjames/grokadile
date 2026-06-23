package com.grokadile.core.common

/**
 * Lightweight result type used across the data/domain boundary so callers can
 * branch on success/failure without exceptions leaking through layers.
 */
sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

/** Normalized error model. Repositories map raw throwables into these. */
sealed class AppError(open val message: String, open val cause: Throwable? = null) {
    data class Network(override val message: String, override val cause: Throwable? = null) :
        AppError(message, cause)

    data class Http(val code: Int, override val message: String, override val cause: Throwable? = null) :
        AppError(message, cause)

    data class Serialization(override val message: String, override val cause: Throwable? = null) :
        AppError(message, cause)

    data class Storage(override val message: String, override val cause: Throwable? = null) :
        AppError(message, cause)

    data class Unknown(override val message: String, override val cause: Throwable? = null) :
        AppError(message, cause)
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(data)
    return this
}

inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) action(error)
    return this
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.data
