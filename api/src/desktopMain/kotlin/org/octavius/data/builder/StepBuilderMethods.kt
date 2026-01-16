package org.octavius.data.builder

import org.octavius.data.transaction.TransactionStep
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Interface for StepBuilder - contains the same terminal methods as TerminalReturningMethods
 * and TerminalModificationMethods, but returns TransactionStep instead of executing queries.
 */
interface StepBuilderMethods {
    // --- Returning full rows ---

    /** Creates a TransactionStep with toList method */
    fun toList(params: Map<String, Any?> = emptyMap()): TransactionStep<List<Map<String, Any?>>>

    /** Creates a TransactionStep with toSingle method */
    fun toSingle(params: Map<String, Any?> = emptyMap()): TransactionStep<Map<String, Any?>?>

    // --- Returning data class objects ---

    /** Creates a TransactionStep with toListOf method */
    fun <T : Any> toListOf(kClass: KClass<T>, params: Map<String, Any?> = emptyMap()): TransactionStep<List<T>>

    /** Creates a TransactionStep with toSingleOf method */
    fun <T : Any> toSingleOf(kClass: KClass<T>, params: Map<String, Any?> = emptyMap()): TransactionStep<T?>

    // --- Returning scalar values ---

    /** Creates a TransactionStep with toField method */
    fun <T : Any> toField(kType: KType, params: Map<String, Any?> = emptyMap()): TransactionStep<T?>

    /** Creates a TransactionStep with toColumn method */
    fun <T : Any> toColumn(kType: KType,  params: Map<String, Any?> = emptyMap()): TransactionStep<List<T?>>

    // --- Modification method ---

    /** Creates a TransactionStep with execute method */
    fun execute(params: Map<String, Any?> = emptyMap()): TransactionStep<Int>
}

fun StepBuilderMethods.toList(vararg params: Pair<String, Any?>): TransactionStep<List<Map<String, Any?>>> =
    toList(params.toMap())

fun StepBuilderMethods.toSingle(vararg params: Pair<String, Any?>): TransactionStep<Map<String, Any?>?> =
    toSingle(params.toMap())

inline fun <reified T : Any> StepBuilderMethods.toField(
    params: Map<String, Any?> = emptyMap()
): TransactionStep<T?> = toField(typeOf<T>(), params)

inline fun <reified T : Any> StepBuilderMethods.toField(
    vararg params: Pair<String, Any?>
): TransactionStep<T?> = toField(typeOf<T>(), params.toMap())

inline fun <reified T : Any> StepBuilderMethods.toColumn(
    params: Map<String, Any?> = emptyMap()
): TransactionStep<List<T?>> = toColumn(typeOf<T>(), params)

inline fun <reified T : Any> StepBuilderMethods.toColumn(
    vararg params: Pair<String, Any?>
): TransactionStep<List<T?>> = toColumn(typeOf<T>(), params.toMap())

fun StepBuilderMethods.execute(vararg params: Pair<String, Any?>): TransactionStep<Int> =
    execute(params.toMap())

inline fun <reified T : Any> StepBuilderMethods.toListOf(vararg params: Pair<String, Any?>): TransactionStep<List<T>> =
    toListOf(T::class, params.toMap())

/**
 * Convenient inline extension functions for StepBuilderMethods
 */
inline fun <reified T : Any> StepBuilderMethods.toListOf(params: Map<String, Any?> = emptyMap()): TransactionStep<List<T>> =
    toListOf(T::class, params)


inline fun <reified T : Any> StepBuilderMethods.toSingleOf(vararg params: Pair<String, Any?>): TransactionStep<T?> =
    toSingleOf(T::class, params.toMap())

inline fun <reified T : Any> StepBuilderMethods.toSingleOf(params: Map<String, Any?> = emptyMap()): TransactionStep<T?> =
    toSingleOf(T::class, params)
