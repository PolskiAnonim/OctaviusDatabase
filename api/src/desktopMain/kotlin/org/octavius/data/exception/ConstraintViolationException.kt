package org.octavius.data.exception


enum class ConstraintViolationExceptionMessage {
    /** A duplicate value was provided for a unique column or set of columns. */
    UNIQUE_CONSTRAINT_VIOLATION,

    /** A value was provided that does not exist in the referenced table. */
    FOREIGN_KEY_VIOLATION,

    /** A null value was provided for a column that is marked as NOT NULL. */
    NOT_NULL_VIOLATION,

    /** A value was provided that does not satisfy the CHECK constraint expression. */
    CHECK_CONSTRAINT_VIOLATION,

    /** General data integrity error (e.g., exclusion constraint or invalid data format). */
    DATA_INTEGRITY
}

/**
 * Errors during SQL execution in the database related to data integrity constraints.
 */
class ConstraintViolationException(
    val messageEnum: ConstraintViolationExceptionMessage,
    val tableName: String? = null,
    val columnName: String? = null,
    val constraintName: String? = null,
    queryContext: QueryContext?,
    cause: Throwable?
) : DatabaseException(
    message = messageEnum.name,
    cause = cause,
    queryContext = queryContext,
    includeCauseInToString = true
) {
    override fun getDetailedMessage(): String {
        return buildString {
            append("\n")
            appendLine("| message: ${generateDeveloperMessage(messageEnum, tableName, columnName, constraintName)}")
            tableName?.let { appendLine("| table: $it") }
            columnName?.let { appendLine("| column: $it") }
            constraintName?.let { appendLine("| constraint: $it") }
        }
    }
}

private fun generateDeveloperMessage(
    messageEnum: ConstraintViolationExceptionMessage,
    tableName: String?,
    columnName: String?,
    constraintName: String?
): String {
    val tableInfo = if (tableName != null) " in table '$tableName'" else ""
    val columnInfo = if (columnName != null) " on column '$columnName'" else ""
    val constraintInfo = if (constraintName != null) " (Constraint: '$constraintName')" else ""

    return when (messageEnum) {
        ConstraintViolationExceptionMessage.UNIQUE_CONSTRAINT_VIOLATION ->
            "Unique constraint violation$tableInfo$columnInfo. A duplicate value was provided for a unique field$constraintInfo."
        
        ConstraintViolationExceptionMessage.FOREIGN_KEY_VIOLATION ->
            "Foreign key violation$tableInfo$columnInfo. The referenced record does not exist$constraintInfo."
        
        ConstraintViolationExceptionMessage.NOT_NULL_VIOLATION ->
            "Not null violation$tableInfo$columnInfo. A null value was provided for a non-nullable field$constraintInfo."
        
        ConstraintViolationExceptionMessage.CHECK_CONSTRAINT_VIOLATION ->
            "Check constraint violation$tableInfo. The value does not satisfy the business rule$constraintInfo."
        
        ConstraintViolationExceptionMessage.DATA_INTEGRITY ->
            "Data integrity violation$tableInfo. The operation would leave the database in an inconsistent state$constraintInfo."
    }
}
