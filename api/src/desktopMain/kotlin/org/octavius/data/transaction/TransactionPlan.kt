package org.octavius.data.transaction

/**
 * Mutable container for building a sequence of database operations to be executed atomically.
 *
 * Collects [TransactionStep] instances and their corresponding [StepHandle]s,
 * enabling deferred execution within a single transaction via [org.octavius.data.DataAccess.executeTransactionPlan].
 *
 * Useful when transaction steps need to be constructed dynamically based on runtime data
 * (e.g., form submissions with variable number of related entities).
 *
 * ### Usage Example
 * ```kotlin
 * val plan = TransactionPlan()
 *
 * val userHandle = plan.add(
 *     dataAccess.insertInto("users").values(userData).asStep().toField<Int>()
 * )
 *
 * plan.add(
 *     dataAccess.insertInto("profiles")
 *         .values(mapOf("user_id" to userHandle.field()))
 *         .asStep().execute()
 * )
 *
 * dataAccess.executeTransactionPlan(plan)
 * ```
 *
 * @see TransactionStep
 * @see StepHandle
 */
class TransactionPlan {
    private val _steps = mutableListOf<Pair<StepHandle<*>, TransactionStep<*>>>()
    val steps: List<Pair<StepHandle<*>, TransactionStep<*>>>
        get() = _steps.toList()

    /**
     * Adds a step to the plan and returns a handle for referencing its result in subsequent steps.
     *
     * @param step Transaction step to add.
     * @return Handle that can be used to reference this step's result in later steps.
     */
    fun <T> add(step: TransactionStep<T>): StepHandle<T> {
        val handle = StepHandle<T>()
        _steps.add(handle to step)
        return handle
    }

    /**
     * Adds all steps from another transaction plan to the current plan.
     *
     * @param otherPlan Transaction plan whose steps will be added.
     */
    fun addPlan(otherPlan: TransactionPlan) {
        // Direct addition of all (handle, step) pairs from another plan
        _steps.addAll(otherPlan.steps)
    }
}
