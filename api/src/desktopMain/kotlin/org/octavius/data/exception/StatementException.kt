package org.octavius.data.exception

enum class StatementExceptionMessage {
    /** SQL syntax error or malformed statement (SQLSTATE Class 426xx). */
    SYNTAX_ERROR,

    /** Table, column, function, or other database object not found (SQLSTATE Class 42Pxx, 427xx). */
    OBJECT_NOT_FOUND,

    /** Insufficient privileges to perform the operation (SQLSTATE 42501). */
    PERMISSION_DENIED,

    /** Invalid password or role for authentication (SQLSTATE Class 28). */
    INVALID_AUTHORIZATION,

    /** Invalid data format, value out of range, or division by zero (SQLSTATE Class 22). */
    DATA_EXCEPTION
}

class StatementException(
    val messageEnum: StatementExceptionMessage,
    val detail: String? = null,
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
            appendLine("| message: ${generateDeveloperMessage(messageEnum)}")
        }
    }
}

private fun generateDeveloperMessage(
    messageEnum: StatementExceptionMessage
): String {
    return when (messageEnum) {
        StatementExceptionMessage.SYNTAX_ERROR -> 
            "SQL syntax error. The statement is malformed or contains invalid syntax."
        
        StatementExceptionMessage.OBJECT_NOT_FOUND -> 
            "Database object not found. Ensure that the table, column, or function exists and is correctly spelled."
        
        StatementExceptionMessage.PERMISSION_DENIED -> 
            "Permission denied. The current user lacks sufficient privileges to perform this operation."
        
        StatementExceptionMessage.INVALID_AUTHORIZATION -> 
            "Invalid authorization. Authentication failed due to incorrect credentials or role permissions."
        
        StatementExceptionMessage.DATA_EXCEPTION -> 
            "Data exception. The provided data is invalid (e.g., value out of range, division by zero, or incorrect format)."
    }
}
