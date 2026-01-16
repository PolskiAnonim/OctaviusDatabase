package org.octavius.data.transaction

import java.util.*

/**
 * Type-safe, unique identifier for a step in a transaction.
 */
class StepHandle<T> internal constructor() {
    private val id: UUID = UUID.randomUUID()

    /**
     * Creates a reference to a scalar value when the step returns a single value
     * (e.g., from `toField()` or `execute()`).
     *
     * @param rowIndex Row index (typically 0).
     */
    fun field(rowIndex: Int = 0): TransactionValue.FromStep.Field {
        return TransactionValue.FromStep.Field(this, rowIndex)
    }

    /**
     * Creates a reference to a value in a specific column when the step returns row(s)
     * (e.g., from `toList()` or `toSingle()`).
     *
     * @param columnName Name of the column to fetch.
     * @param rowIndex Row index (typically 0).
     */
    fun field(columnName: String, rowIndex: Int = 0): TransactionValue.FromStep.Field {
        return TransactionValue.FromStep.Field(this, columnName, rowIndex)
    }

    /** Fetches an entire column from a result that is a list of scalars (result of `toColumn()`). */
    fun column(): TransactionValue.FromStep.Column {
        return TransactionValue.FromStep.Column(this)
    }

    /** Fetches values from a given column from a result that is a list of rows (result of `toList()`). */
    fun column(columnName: String): TransactionValue.FromStep.Column {
        return TransactionValue.FromStep.Column(this, columnName)
    }

    /**
     * Creates a reference to an entire row as `Map<String, Any?>` from a result that is a list of rows.
     *
     * Useful when you want to pass multiple fields from one result as parameters
     * to the next step. The executor will "spread" the map into individual parameters.
     *
     * @param rowIndex Row index (default 0, i.e., first row).
     */
    fun row(rowIndex: Int = 0): TransactionValue.FromStep.Row {
        return TransactionValue.FromStep.Row(this, rowIndex)
    }

    override fun equals(other: Any?) = other is StepHandle<*> && id == other.id
    override fun hashCode() = id.hashCode()
    override fun toString(): String = "StepHandle(id=$id)"
}