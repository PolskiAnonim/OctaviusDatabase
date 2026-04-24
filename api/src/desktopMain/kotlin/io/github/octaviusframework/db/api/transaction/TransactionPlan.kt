package io.github.octaviusframework.db.api.transaction

/**
 * Mutable container for building a sequence of database operations to be executed atomically.
 *
 * Collects [TransactionStep] instances and their corresponding [StepHandle]s,
 * enabling deferred execution within a single transaction via [DataAccess.executeTransactionPlan][io.github.octaviusframework.db.api.DataAccess.executeTransactionPlan].
 *
 * Useful when transaction steps need to be constructed dynamically based on runtime data
 * (e.g., enrolling a variable number of legionnaires into a newly created legion).
 *
 * ### Usage Example
 * ```kotlin
 * val plan = TransactionPlan()
 *
 * // Step 1: Create the legion, get its generated ID back
 * val legionHandle = plan.add(
 *     dataAccess.insertInto("legions").values(legionData).asStep().toField<Int>()
 * )
 *
 * // Step 2: Enroll each legionnaire, referencing the legion ID from Step 1
 * legionnaires.forEach { legionnaire ->
 *     plan.add(
 *         dataAccess.insertInto("legionnaire_assignments")
 *             .values(mapOf("legion_id" to legionHandle.field(), "legionnaire_id" to legionnaire.id))
 *             .asStep().execute()
 *     )
 * }
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
