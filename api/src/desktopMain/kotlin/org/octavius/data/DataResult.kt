package org.octavius.data

import org.octavius.data.exception.DatabaseException

/**
 * Container for a database operation result, which can end in success or failure.
 * Replaces the exception mechanism, forcing explicit handling of both cases.
 *
 * @param T Data type in case of success.
 */
sealed class DataResult<out T> {
    /** Represents successful operation execution. */
    data class Success<out T>(val value: T) : DataResult<T>()

    /** Represents an error during operation. */
    data class Failure(val error: DatabaseException) : DataResult<Nothing>()
}

/**
 * Transforms the value inside [DataResult.Success], leaving [DataResult.Failure] unchanged.
 *
 * Allows for clean and safe operation on the result without throwing exceptions.
 * Example: `result.map { it.toString() }` converts Success<Int> to Success<String>.
 *
 * @param T Original value type.
 * @param R Value type after transformation.
 * @param transform Function transforming value of type T to type R.
 * @return New DataResult with transformed value or original Failure.
 */
inline fun <T, R> DataResult<T>.map(transform: (T) -> R): DataResult<R> {
    return when (this) {
        is DataResult.Success -> DataResult.Success(transform(value))
        is DataResult.Failure -> this
    }
}

/**
 * Executes an action if the result is Success, without modifying the original value.
 *
 * @param action Action to execute on the Success value.
 * @return The same DataResult for chaining.
 */
fun <T> DataResult<T>.onSuccess(action: (T) -> Unit): DataResult<T> {
    if (this is DataResult.Success) action(value)
    return this
}

/**
 * Executes an action if the result is Failure, without modifying the original error.
 *
 * @param action Action to execute on the Failure error.
 * @return The same DataResult for chaining.
 */
fun <T> DataResult<T>.onFailure(action: (DatabaseException) -> Unit): DataResult<T> {
    if (this is DataResult.Failure) action(error)
    return this
}

/**
 * Returns the value if the result is Success, or throws an exception if it is Failure.
 *
 * Use with caution - this method breaks safe error processing.
 * Prefer [map], [onSuccess], [onFailure] or [getOrElse] when possible.
 *
 * @return Value of type T from Success.
 * @throws DatabaseException if the result is Failure.
 */
fun <T> DataResult<T>.getOrThrow(): T {
    return when (this) {
        is DataResult.Success -> this.value
        is DataResult.Failure -> throw this.error
    }
}

/**
 * Returns the value if the result is Success, or computes a default value from the error if it is Failure.
 *
 * Allows for safe "exit" from DataResult with an always defined value.
 * Example: `result.getOrElse { emptyList() }` will return an empty list in case of error.
 *
 * @param R Return value type (supertype of T).
 * @param T Value type in Success.
 * @param onFailure Function computing the default value based on the error.
 * @return Value from Success or result of onFailure function.
 */
fun <R, T : R> DataResult<T>.getOrElse(onFailure: (Throwable) -> R): R {
    return when (this) {
        is DataResult.Success -> this.value
        is DataResult.Failure -> onFailure.invoke(this.error)
    }
}