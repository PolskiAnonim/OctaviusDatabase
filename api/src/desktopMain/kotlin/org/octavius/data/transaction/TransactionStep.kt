package org.octavius.data.transaction

import org.octavius.data.DataResult
import org.octavius.data.assertNotNull
import org.octavius.data.builder.QueryBuilder

/**
 * Encapsulates a single, deferred database operation within a [TransactionPlan].
 *
 * Created via [org.octavius.data.builder.QueryBuilder.asStep] and terminal methods
 * like `toField()`, `toList()`, or `execute()`. The step is not executed immediately;
 * instead, it stores all information needed for later execution within a transaction.
 *
 * @param T The type of result this step will produce when executed.
 * @property builder The query builder containing the SQL structure.
 * @property executionLogic Function that executes the query and returns the result.
 * @property params Query parameters to be passed during execution.
 *
 * @see TransactionPlan
 * @see StepHandle
 */
class TransactionStep<T>(
    // All fields must be public so that Executor has access to them
    val builder: QueryBuilder<*>,
    val executionLogic: (builder: QueryBuilder<*>, params: Map<String, Any?>) -> DataResult<T>,
    val params: Map<String, Any?>
) {
    override fun toString(): String {
        return """
            TransactionStep{
                builder: 
                $builder
                ------------------------------
                params: 
                $params
            }
        """.trimIndent()

    }
}

fun <T : Any> TransactionStep<T?>.assertNotNull(): TransactionStep<T> {
    return TransactionStep(
        builder = this.builder,
        params = this.params,
        executionLogic = { builder, finalParams ->
            val originalResult = this.executionLogic(builder, finalParams)
            originalResult.assertNotNull()
        }
    )
}
