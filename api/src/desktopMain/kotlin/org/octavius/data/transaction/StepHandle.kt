package org.octavius.data.transaction

import java.util.*

/**
 * Type-safe, unique identifier for a step in a transaction.
 * @param T The expected result type of the step this handle refers to.
 * For example, `Int` for a single value, `List<Map<String, Any?>>` for a list of rows.
 */
class StepHandle<T> internal constructor() {
    private val id: UUID = UUID.randomUUID()

    /**
     * Creates a reference to a scalar value when the step returns a single value.
     * The type of the value is inferred from the handle's type parameter `T`.
     *
     * @param rowIndex Row index (typically 0).
     */
    fun field(rowIndex: Int = 0): TransactionValue.FromStep.Field<T> {
        return TransactionValue.FromStep.Field(this, rowIndex)
    }

    /**
     * Creates a reference to a value in a specific column.
     * You must specify the expected type of the value.
     *
     * @param V The expected type of the field's value.
     * @param columnName Name of the column to fetch.
     * @param rowIndex Row index (typically 0).
     */
    fun <V> field(columnName: String, rowIndex: Int = 0): TransactionValue.FromStep.Field<V> {
        return TransactionValue.FromStep.Field(this, columnName, rowIndex)
    }

    /**
     * Fetches an entire column from a result that is a list of scalars.
     * You must specify the expected type of the elements in the column.
     *
     * @param V The expected type of elements in the column.
     */
    fun <V> column(): TransactionValue.FromStep.Column<V> {
        return TransactionValue.FromStep.Column(this)
    }

    /**
     * Fetches values from a given column from a result that is a list of rows.
     * You must specify the expected type of the elements in the column.
     *
     * @param V The expected type of elements in the column.
     */
    fun <V> column(columnName: String): TransactionValue.FromStep.Column<V> {
        return TransactionValue.FromStep.Column(this, columnName)
    }

    /**
     * Creates a reference to an entire row as `Map<String, Any?>`.
     * The return type is fixed to `TransactionValue<Map<String, Any?>>`.
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