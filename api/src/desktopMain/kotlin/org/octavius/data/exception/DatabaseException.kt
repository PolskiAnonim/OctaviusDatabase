package org.octavius.data.exception

/**
 * Base sealed exception for all Octavius Database errors.
 */
sealed class DatabaseException(
    message: String,
    queryContext: QueryContext?,
    cause: Throwable? = null
): RuntimeException(message, cause) {

    private var _queryContext: QueryContext? = queryContext
    val queryContext: QueryContext? get() = _queryContext

    /**
     * Enriches the exception with the transaction step index.
     */
    fun withStepIndex(index: Int): DatabaseException {
        _queryContext = _queryContext?.withTransactionStep(index) 
            ?: QueryContext(sql = "", parameters = emptyMap(), transactionStepIndex = index)
        return this
    }

    /**
     * Enriches the exception with a full query context.
     */
    fun withContext(context: QueryContext): DatabaseException {
        _queryContext = context
        return this
    }

    override fun toString(): String {
        val contextStr = queryContext?.toString() ?: ""
        val nestedError = cause?.toString()?.prependIndent("|   ") ?: "|   No cause available"

        return """
$contextStr

------------------------------------------------------------
| ERROR: ${this::class.simpleName}
| MESSAGE: $message
------------------------------------------------------------
| CAUSE:
------------------------------------------------------------
$nestedError
------------------------------------------------------------
        """.trimIndent()
    }
}

/**
 * Errors in the application code or framework logic (e.g., mapping failures, dependency errors).
 * Errors that probably can't be fixed without database or code changes
 */
sealed class CodeExecutionException(
    val details: String,
    queryContext: QueryContext?,
    cause: Throwable?
) : DatabaseException(details, queryContext, cause)

/**
 * Errors during SQL execution in the database (e.g., constraint violations, syntax errors).
 */
class DatabaseExecutionException(
    val errorType: DbErrorType,
    val constraintName: String? = null,
    queryContext: QueryContext?,
    cause: Throwable?
) : DatabaseException("DB Execution failed: $errorType${constraintName?.let { " (Constraint: $it)" } ?: ""}", queryContext, cause)

/**
 * Infrastructure and connectivity issues.
 */
class ConnectionException(
    message: String,
    cause: Throwable?
) : DatabaseException(message, null, cause)

/**
 * Concurrency and transaction-related issues (e.g., deadlocks, timeouts).
 */
class ConcurrencyException(
    val errorType: ConcurrencyErrorType,
    queryContext: QueryContext?,
    cause: Throwable?
) : DatabaseException("Concurrency error: $errorType", queryContext, cause)


enum class DbErrorType {
    UNIQUE_CONSTRAINT_VIOLATION,
    FOREIGN_KEY_VIOLATION,
    NOT_NULL_VIOLATION,
    CHECK_CONSTRAINT_VIOLATION,
    BAD_SQL_GRAMMAR,
    DATA_INTEGRITY,
    UNKNOWN
}

enum class ConcurrencyErrorType {
    TIMEOUT,
    DEADLOCK,
    OPTIMISTIC_LOCK,
    CONCURRENT_MODIFICATION
}
