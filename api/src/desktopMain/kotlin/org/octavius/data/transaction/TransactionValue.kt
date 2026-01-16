package org.octavius.data.transaction

/**
 * Represents a value in a transaction step.
 * Enables passing both constant values and dynamic references
 * to results of previous steps in the same transaction.
 */
sealed class TransactionValue {
    /**
     * Constant, predefined value.
     * @param value Value to use in the operation.
     */
    data class Value(val value: Any?) : TransactionValue()

    /**
     * Reference to the result from a previous step. This class is the base for
     * more specific reference types.
     */
    sealed class FromStep(open val handle: StepHandle<*>) : TransactionValue() {
        /**
         * Fetches a single value from a specific cell (`row`, `column`).
         * Ideal for retrieving the ID from a just-inserted row.
         *
         * @param handle Handle to the step from which the data originates.
         * @param columnName Name of the column from which the value should be fetched.
         * @param rowIndex Row index (default 0, i.e., first).
         */
        data class Field(
            override val handle: StepHandle<*>,
            val columnName: String?,
            val rowIndex: Int = 0
        ) : FromStep(handle) {
            constructor(handle: StepHandle<*>, rowIndex: Int = 0) : this(handle, null, rowIndex)
        }

        /**
         * Fetches all values from one column as a list or typed array.
         *
         * Used mainly for passing results from one query as parameters
         * to another, e.g., in clauses like `WHERE id = ANY(:ids)` or `INSERT ... SELECT ... FROM UNNEST(...)`.
         *
         * @param handle Handle to the step from which the data originates.
         * @param columnName Name of the column whose values should be fetched.
         */
        data class Column(
            override val handle: StepHandle<*>,
            val columnName: String?
        ) : FromStep(handle) {
            constructor(handle: StepHandle<*>) : this(handle, null)
        }

        /**
         * Fetches an entire row as `Map<String, Any?>`.
         * Useful when you want to pass multiple fields from one result as parameters
         * to the next step (e.g., copying a row with modifications).
         * The Executor specially handles this type by "spreading" the map into parameters.
         *
         * @param handle Handle to the step from which the data originates.
         * @param rowIndex Row index (default 0, i.e., first).
         */
        data class Row(
            override val handle: StepHandle<*>,
            val rowIndex: Int = 0
        ) : FromStep(handle)
    }

    /**
     * Result of transforming another value.
     */
    class Transformed(
        val source: TransactionValue,
        val transform: (Any?) -> Any?
    ) : TransactionValue()
}

fun TransactionValue.map(transformation: (Any?) -> Any?): TransactionValue {
    return TransactionValue.Transformed(this, transformation)
}

/**
 * Converts any value (including null) to an instance of [TransactionValue.Value].
 *
 * Provides a concise alternative to explicit constructor invocation,
 * improving readability of operations building transaction steps.
 *
 * Usage example:
 * `val idRef = 123.toTransactionValue()` instead of `val idRef = TransactionValue.Value(123)`
 *
 * @return Instance of [TransactionValue.Value] wrapping this value.
 * @see TransactionValue
 */
fun Any?.toTransactionValue(): TransactionValue = TransactionValue.Value(this)
