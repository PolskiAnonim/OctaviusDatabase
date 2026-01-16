package org.octavius.data.transaction

import org.octavius.data.exception.StepDependencyException
import org.octavius.data.exception.StepDependencyExceptionMessage

/**
 * Stores the results of an executed transaction plan in a safe manner.
 * Allows retrieving results using `StepHandle` instead of indices.
 */
class TransactionPlanResult(private val results: Map<StepHandle<*>, Any?>) {

    /**
     * Gets the result for a specific step.
     * @param handle Handle to the step whose result we want to retrieve.
     * @return Result of type defined in the handle (`T`)
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> get(handle: StepHandle<T>): T {
        return if (results.containsKey(handle)) {
            results[handle] as T
        } else {
            throw StepDependencyException(StepDependencyExceptionMessage.UNKNOWN_STEP_HANDLE, -1)
        }
    }
}