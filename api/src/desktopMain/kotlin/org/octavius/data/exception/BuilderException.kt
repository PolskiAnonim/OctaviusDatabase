package org.octavius.data.exception

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * Exception thrown when a query cannot be built correctly due to invalid state
 * or missing mandatory clauses (e.g., DELETE without WHERE).
 *
 * This exception indicates a programmer error during query construction
 * and is intended to be thrown immediately, bypassing the DataResult pattern.
 */
class BuilderException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Throws a [BuilderException] with the result of calling [lazyMessage] if the [value] is false.
 */
@OptIn(ExperimentalContracts::class)
inline fun checkBuilder(value: Boolean, lazyMessage: () -> Any) {
    contract {
        returns() implies value
    }
    if (!value) {
        throw BuilderException(lazyMessage().toString())
    }
}

/**
 * Throws a [BuilderException] with the result of calling [lazyMessage] if the [value] is false.
 */
@OptIn(ExperimentalContracts::class)
inline fun requireBuilder(value: Boolean, lazyMessage: () -> Any) {
    contract {
        returns() implies value
    }
    if (!value) {
        throw BuilderException(lazyMessage().toString())
    }
}
