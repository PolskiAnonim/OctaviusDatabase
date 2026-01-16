package org.octavius.database.builder

import kotlinx.coroutines.*
import org.octavius.data.DataResult
import org.octavius.data.builder.AsyncTerminalMethods
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Internal implementation of AsyncTerminalMethods.
 *
 * Executes database queries asynchronously on the IO dispatcher and returns results
 * to the original coroutine context (e.g., UI).
 */
internal class AsyncQueryBuilder(
    private val builder: AbstractQueryBuilder<*>,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher
) : AsyncTerminalMethods {

    private suspend fun <T> executeAndInvoke(
        query: () -> DataResult<T>,
        onResult: (DataResult<T>) -> Unit
    ) {
        val result = withContext(ioDispatcher) {
            query()
        }
        withContext(scope.coroutineContext) { // Return to original context (e.g., UI)
            onResult(result)
        }
    }

    override fun toList(
        params: Map<String, Any?>,
        onResult: (DataResult<List<Map<String, Any?>>>) -> Unit
    ): Job = scope.launch {
        executeAndInvoke({ builder.toList(params) }, onResult)
    }

    override fun toSingle(
        params: Map<String, Any?>,
        onResult: (DataResult<Map<String, Any?>?>) -> Unit
    ): Job = scope.launch {
        executeAndInvoke({ builder.toSingle(params) }, onResult)
    }

    override fun <T : Any> toListOf(
        kClass: KClass<T>,
        params: Map<String, Any?>,
        onResult: (DataResult<List<T>>) -> Unit
    ): Job = scope.launch {
        executeAndInvoke({ builder.toListOf(kClass, params) }, onResult)
    }

    override fun <T : Any> toSingleOf(
        kClass: KClass<T>,
        params: Map<String, Any?>,
        onResult: (DataResult<T?>) -> Unit
    ): Job = scope.launch {
        executeAndInvoke({ builder.toSingleOf(kClass, params) }, onResult)
    }

    override fun <T : Any> toField(
        kType: KType,
        params: Map<String, Any?>,
        onResult: (DataResult<T?>) -> Unit
    ): Job = scope.launch {
        executeAndInvoke({ builder.toField(kType, params) }, onResult)
    }

    override fun <T : Any> toColumn(
        kType: KType,
        params: Map<String, Any?>,
        onResult: (DataResult<List<T?>>) -> Unit
    ): Job = scope.launch {
        executeAndInvoke({ builder.toColumn(kType, params) }, onResult)
    }

    override fun execute(
        params: Map<String, Any?>,
        onResult: (DataResult<Int>) -> Unit
    ): Job = scope.launch {
        executeAndInvoke({ builder.execute(params) }, onResult)
    }
}
