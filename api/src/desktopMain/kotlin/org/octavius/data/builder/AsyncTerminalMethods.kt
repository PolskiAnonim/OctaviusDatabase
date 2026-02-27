package org.octavius.data.builder

import kotlinx.coroutines.Job
import org.octavius.data.DataResult
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Interface for builder executing queries asynchronously in the provided CoroutineScope.
 * Terminal methods accept callbacks and return Job for lifecycle control.
 */
interface AsyncTerminalMethods {
    // --- Methods returning full rows ---

    /** Asynchronously fetches a list of rows and passes the result to the onResult callback. */
    fun toList(
        params: Map<String, Any?> = emptyMap(),
        onResult: (DataResult<List<Map<String, Any?>>>) -> Unit
    ): Job

    /** Asynchronously fetches a single row and passes the result to the onResult callback. */
    fun toSingle(
        params: Map<String, Any?> = emptyMap(),
        onResult: (DataResult<Map<String, Any?>?>) -> Unit
    ): Job

    /** Asynchronously fetches a single row (non-nullable) and passes the result to the onResult callback. */
    fun toSingleNotNull(
        params: Map<String, Any?> = emptyMap(),
        onResult: (DataResult<Map<String, Any?>>) -> Unit
    ): Job

    // --- Methods returning data class objects ---

    /** Asynchronously maps results to a list of objects and passes them to the onResult callback. */
    fun <T> toListOf(
        kType: KType,
        params: Map<String, Any?> = emptyMap(),
        onResult: (DataResult<List<T>>) -> Unit
    ): Job

    /** Asynchronously maps the result to a single object and passes it to the onResult callback. */
    fun <T> toSingleOf(
        kType: KType,
        params: Map<String, Any?> = emptyMap(),
        onResult: (DataResult<T>) -> Unit
    ): Job

    // --- Methods returning scalar values ---

    /** Asynchronously fetches a single value from the first column and passes the result to the onResult callback. */
    fun <T> toField(
        kType: KType,
        params: Map<String, Any?> = emptyMap(),
        onResult: (DataResult<T>) -> Unit
    ): Job

    /** Asynchronously fetches a list of values from the first column and passes the result to the onResult callback. */
    fun <T> toColumn(
        kType: KType,
        params: Map<String, Any?> = emptyMap(),
        onResult: (DataResult<List<T>>) -> Unit
    ): Job

    // --- Modification method ---

    /** Asynchronously executes a modification query and passes the result to the onResult callback. */
    fun execute(
        params: Map<String, Any?> = emptyMap(),
        onResult: (DataResult<Int>) -> Unit
    ): Job
}

fun AsyncTerminalMethods.toList(
    vararg params: Pair<String, Any?>,
    onResult: (DataResult<List<Map<String, Any?>>>) -> Unit
): Job = toList(params.toMap(), onResult)

fun AsyncTerminalMethods.toSingle(
    vararg params: Pair<String, Any?>,
    onResult: (DataResult<Map<String, Any?>?>) -> Unit
): Job = toSingle(params.toMap(), onResult)

fun AsyncTerminalMethods.toSingleNotNull(
    vararg params: Pair<String, Any?>,
    onResult: (DataResult<Map<String, Any?>>) -> Unit
): Job = toSingleNotNull(params.toMap(), onResult)

// Inline extensions
inline fun <reified T : Any> AsyncTerminalMethods.toListOf(
    params: Map<String, Any?> = emptyMap(),
    noinline onResult: (DataResult<List<T>>) -> Unit
): Job = toListOf(typeOf<T>(), params, onResult)

inline fun <reified T> AsyncTerminalMethods.toSingleOf(
    params: Map<String, Any?> = emptyMap(),
    noinline onResult: (DataResult<T>) -> Unit
): Job = toSingleOf(typeOf<T>(), params, onResult)

inline fun <reified T : Any> AsyncTerminalMethods.toListOf(
    vararg params: Pair<String, Any?>,
    noinline onResult: (DataResult<List<T>>) -> Unit
): Job = toListOf(typeOf<T>(), params.toMap(), onResult)

inline fun <reified T> AsyncTerminalMethods.toSingleOf(
    vararg params: Pair<String, Any?>,
    noinline onResult: (DataResult<T>) -> Unit
): Job = toSingleOf(typeOf<T>(), params.toMap(), onResult)

inline fun <reified T> AsyncTerminalMethods.toField(
    params: Map<String, Any?> = emptyMap(),
    noinline onResult: (DataResult<T>) -> Unit
): Job = toField(typeOf<T>(), params, onResult)

inline fun <reified T> AsyncTerminalMethods.toField(
    vararg params: Pair<String, Any?>,
    noinline onResult: (DataResult<T>) -> Unit
): Job = toField(typeOf<T>(), params.toMap(), onResult)

// toColumn
inline fun <reified T> AsyncTerminalMethods.toColumn(
    params: Map<String, Any?> = emptyMap(),
    noinline onResult: (DataResult<List<T>>) -> Unit
): Job = toColumn(typeOf<T>(), params, onResult)

inline fun <reified T> AsyncTerminalMethods.toColumn(
    vararg params: Pair<String, Any?>,
    noinline onResult: (DataResult<List<T>>) -> Unit
): Job = toColumn(typeOf<T>(), params.toMap(), onResult)
