package org.octavius.data.builder

import org.octavius.data.DataResult
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/** Interface containing terminal methods that return data */
interface TerminalReturningMethods {
    // --- Returning full rows ---

    /** Fetches a list of rows as List<Map<String, Any?>>. */
    fun toList(params: Map<String, Any?> = emptyMap()): DataResult<List<Map<String, Any?>>>

    /** Fetches a single row as Map<String, Any?>?. Returns Success(null) if no rows. */
    fun toSingle(params: Map<String, Any?> = emptyMap()): DataResult<Map<String, Any?>?>

    /** Fetches a single row as Map<String, Any?>. Returns Failure if no rows. */
    fun toSingleNotNull(params: Map<String, Any?> = emptyMap()): DataResult<Map<String, Any?>>

    // --- Returning data class objects ---

    /**
     * Maps results to a list of objects of the given type.
     * Requires that column names/aliases in SQL (in snake_case convention) match
     * property names in the target class (in camelCase convention) or have a @MapKey annotation
     * with the stored column name.
     */
    fun <T> toListOf(kType: KType, params: Map<String, Any?> = emptyMap()): DataResult<List<T>>

    /**
     * Maps the result to a single object of the given type.
     * Works on the same mapping principle as `toListOf`.
     * Nullability is determined by the KType: use `toSingleOf<User>()` for non-null
     * or `toSingleOf<User?>()` for nullable results.
     */
    fun <T> toSingleOf(kType: KType, params: Map<String, Any?> = emptyMap()): DataResult<T>

    // --- Returning scalar values ---

    /**
     * Fetches a single value from the first column of the first row.
     * Nullability is determined by the KType: use `toField<Int>()` for non-null
     * or `toField<Int?>()` for nullable results.
     */
    fun <T> toField(targetType: KType, params: Map<String, Any?> = emptyMap()): DataResult<T>

    /**
     * Fetches a list of values from the first column of all rows.
     * Nullability of elements is determined by the KType: use `toColumn<Int>()` for non-null
     * or `toColumn<Int?>()` for nullable elements.
     */
    fun <T> toColumn(targetType: KType, params: Map<String, Any?> = emptyMap()): DataResult<List<T>>

    // --- Helper methods ---

    /** Returns the SQL string without executing the query. */
    fun toSql(): String
}

fun TerminalReturningMethods.toList(vararg params: Pair<String, Any?>): DataResult<List<Map<String, Any?>>> =
    toList(params.toMap())

fun TerminalReturningMethods.toSingle(vararg params: Pair<String, Any?>): DataResult<Map<String, Any?>?> =
    toSingle(params.toMap())

fun TerminalReturningMethods.toSingleNotNull(vararg params: Pair<String, Any?>): DataResult<Map<String, Any?>> =
    toSingleNotNull(params.toMap())

inline fun <reified T> TerminalReturningMethods.toField(
    params: Map<String, Any?> = emptyMap()
): DataResult<T> = toField(typeOf<T>(), params)

inline fun <reified T> TerminalReturningMethods.toField(
    vararg params: Pair<String, Any?>
): DataResult<T> = toField(typeOf<T>(), params.toMap())

inline fun <reified T> TerminalReturningMethods.toColumn(
    params: Map<String, Any?> = emptyMap()
): DataResult<List<T>> = toColumn(typeOf<T>(), params)

inline fun <reified T> TerminalReturningMethods.toColumn(
    vararg params: Pair<String, Any?>
): DataResult<List<T>> = toColumn(typeOf<T>(), params.toMap())

inline fun <reified T : Any> TerminalReturningMethods.toListOf(vararg params: Pair<String, Any?>): DataResult<List<T>> =
    toListOf(typeOf<T>(), params.toMap())

/**
 * Convenient `inline` extension function for toListOf.
 * Uses `reified` to automatically infer the target type.
 */
inline fun <reified T : Any> TerminalReturningMethods.toListOf(params: Map<String, Any?> = emptyMap()): DataResult<List<T>> =
    toListOf(typeOf<T>(), params)

inline fun <reified T> TerminalReturningMethods.toSingleOf(vararg params: Pair<String, Any?>): DataResult<T> =
    toSingleOf(typeOf<T>(), params.toMap())

/**
 * Convenient `inline` extension function for toSingleOf.
 * Uses `reified` to automatically infer the target type.
 */
inline fun <reified T> TerminalReturningMethods.toSingleOf(params: Map<String, Any?> = emptyMap()): DataResult<T> =
    toSingleOf(typeOf<T>(), params)


/** Interface containing terminal modification method */
interface TerminalModificationMethods {
    /** Executes the query and returns the number of rows that were updated */
    fun execute(params: Map<String, Any?> = emptyMap()): DataResult<Int>
}

fun TerminalModificationMethods.execute(vararg params: Pair<String, Any?>): DataResult<Int> =
    execute(params.toMap())
