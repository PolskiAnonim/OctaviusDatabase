package org.octavius.data.transaction

/**
 * Represents a value in a transaction step.
 * Enables passing both constant values and dynamic references
 * to results of previous steps in the same transaction.
 * @param T The type of the value represented by this instance.
 */
sealed class TransactionValue<T> {
    /**
     * Constant, predefined value.
     * @param T The type of the value.
     * @param value Value to use in the operation.
     */
    data class Value<T>(val value: T) : TransactionValue<T>()

    /**
     * Reference to the result from a previous step. This class is the base for
     * more specific reference types.
     * @param T The type of the value being referenced.
     */
    sealed class FromStep<T>(open val handle: StepHandle<*>) : TransactionValue<T>() {
        /**
         * Fetches a single value from a specific cell (`row`, `column`).
         * Ideal for retrieving the ID from a just-inserted row.
         *
         * @param T The expected type of the field's value.
         * @param handle Handle to the step from which the data originates.
         * @param columnName Name of the column from which the value should be fetched.
         * @param rowIndex Row index (default 0, i.e., first).
         */
        data class Field<T>(
            override val handle: StepHandle<*>,
            val columnName: String?,
            val rowIndex: Int = 0
        ) : FromStep<T>(handle) {
            constructor(handle: StepHandle<*>, rowIndex: Int = 0) : this(handle, null, rowIndex)
        }

        /**
         * Fetches all values from one column as a list.
         *
         * Used mainly for passing results from one query as parameters
         * to another, e.g., in clauses like `WHERE id = ANY(:ids)` or `INSERT ... SELECT ... FROM UNNEST(...)`.
         *
         * @param T The expected type of elements in the column. The resulting value will be of type `List<T>`.
         * @param handle Handle to the step from which the data originates.
         * @param columnName Name of the column whose values should be fetched.
         */
        data class Column<T>(
            override val handle: StepHandle<*>,
            val columnName: String?
        ) : FromStep<List<T>>(handle) {
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
        ) : FromStep<Map<String, Any?>>(handle)
    }

    /**
     * Result of transforming another value.
     * @param IN The input type of the transformation.
     * @param OUT The output type of the transformation.
     */
    class Transformed<IN, OUT>(
        val source: TransactionValue<IN>,
        val transform: (IN) -> OUT
    ) : TransactionValue<OUT>()
}

/**
 * Applies a transformation to a [TransactionValue], creating a new, transformed value.
 * This function is type-safe.
 *
 * @param IN The input type.
 * @param OUT The output type.
 * @param transformation A function that converts a value of type [IN] to [OUT].
 * @return A new [TransactionValue] of type [OUT].
 */
fun <IN, OUT> TransactionValue<IN>.map(transformation: (IN) -> OUT): TransactionValue<OUT> {
    return TransactionValue.Transformed(this, transformation)
}

/**
 * Converts any value to a type-safe instance of [TransactionValue.Value].
 *
 * Provides a concise alternative to explicit constructor invocation,
 * improving readability of operations building transaction steps.
 *
 * Usage example:
 * `val idRef = 123.toTransactionValue()` instead of `val idRef = TransactionValue.Value(123)`
 * The result will be `TransactionValue<Int>`.
 *
 * @return Instance of [TransactionValue.Value] wrapping this value.
 * @see TransactionValue
 */
fun <T> T.toTransactionValue(): TransactionValue.Value<T> = TransactionValue.Value(this)