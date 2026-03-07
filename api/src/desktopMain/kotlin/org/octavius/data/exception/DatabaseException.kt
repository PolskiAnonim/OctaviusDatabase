package org.octavius.data.exception

import org.octavius.data.exception.ExecutionContext

/**
 * Base sealed exception for all data layer errors.
 *
 * All database access related exceptions inherit from this class,
 * enabling easy catching and handling of errors at different application levels.
 */
sealed class DatabaseException(
    message: String,
    cause: Throwable? = null,
    val executionContext: ExecutionContext? = null
) : RuntimeException(message, cause)

/**
 * Errors during SQL query execution.
 *
 * Contains full error context: SQL query and parameters.
 * Thrown when:
 * - SQL query is syntactically incorrect
 * - Database constraint violation
 * - Database connection errors
 */
class QueryExecutionException(
    val sql: String,
    val params: Map<String, Any?>,
    val expandedSql: String? = null,
    val expandedParams: List<Any?>? = null,
    message: String? = null,
    cause: Throwable? = null,
    executionContext: ExecutionContext? = null
) : DatabaseException(
    message ?: "Error during query execution",
    cause,
    executionContext ?: ExecutionContext(sql, params, expandedSql, expandedParams)
) {

    override fun toString(): String {
        val nestedError = cause?.toString()?.prependIndent("|   ") ?: "|   No cause available"

        return """
        
$executionContext

------------------------------------------------------------
| ERROR CAUSE:
------------------------------------------------------------
$nestedError
------------------------------------------------------------
        """.trimIndent()
    }
}

/**
 * Exception thrown when execution of a specific step within a batch transaction fails.
 *
 * Wraps the original exception (e.g., QueryExecutionException), adding context
 * about which step failed.
 *
 * @param stepIndex Index (0-based) of the step that failed.
 * @param cause Original exception that caused the error.
 * @param executionContext Context of the execution, if available.
 */
class TransactionStepExecutionException(
    val stepIndex: Int,
    override val cause: Throwable,
    executionContext: ExecutionContext? = null
) : DatabaseException(
    "Execution of transaction step $stepIndex failed",
    cause,
    executionContext
) {
    override fun toString(): String {
        val nestedError = cause.toString().prependIndent("|   ")
        val contextStr = executionContext?.toString() ?: ""

        return """

$contextStr

-------------------------------------
| TRANSACTION STEP $stepIndex FAILED
-------------------------------------
| Step error details:
$nestedError
-------------------------------------
"""
    }
}

/**
 * Exception thrown when execution of a transaction fails.
 *
 * Wraps the original exception (e.g., ConcurrencyFailureException)
 *
 * @param cause Original exception that caused the error.
 * @param executionContext Context of the execution, if available.
 */
class TransactionException(
    override val cause: Throwable,
    executionContext: ExecutionContext? = null
) : DatabaseException(
    "Execution of transaction failed",
    cause,
    executionContext
) {
    override fun toString(): String {
        val nestedError = cause.toString().prependIndent("|   ")
        val contextStr = executionContext?.toString() ?: ""

        return """

$contextStr

-------------------------------------
| TRANSACTION FAILED
-------------------------------------
| Error details:
$nestedError
-------------------------------------
"""
    }
}
