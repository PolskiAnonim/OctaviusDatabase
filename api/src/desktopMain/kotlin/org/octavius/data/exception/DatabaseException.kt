package org.octavius.data.exception

/**
 * Base sealed exception for all data layer errors.
 *
 * All database access related exceptions inherit from this class,
 * enabling easy catching and handling of errors at different application levels.
 */
sealed class DatabaseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

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
    cause: Throwable? = null
) : DatabaseException(message ?: "Error during query execution", cause) {

    override fun toString(): String {
        val nestedError = cause?.toString()?.prependIndent("|   ") ?: "|   No cause available"

        // Formatowanie parametrów, żeby nie zalały logów
        val formattedExpandedParams = expandedParams?.mapIndexed { index, value ->
            "\n|    [$index] -> $value"
        }?.joinToString("") ?: "null"

        val formattedOriginalParams = params.entries.joinToString(
            prefix = "\n| ",
            separator = "\n| "
        ) { (k, v) -> "$k = $v" }

        val executionDetails = if (expandedSql != null) {
            """
            |
            |---[ Execution Details (Low Level) ]---
            | expandedSql: $expandedSql
            | expandedParams ($expandedParams?.size): $formattedExpandedParams
            """.trimMargin()
        } else ""

        return """
        
        ------------------------------------------------------------
        |  QUERY EXECUTION FAILED
        ------------------------------------------------------------
        | Message: $message
        |
        |---[ Original Query ]---
        | SQL: $sql
        | Params: $formattedOriginalParams
        $executionDetails
        ------------------------------------------------------------
        | Error Cause:
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
 */
class TransactionStepExecutionException(
    val stepIndex: Int,
    override val cause: Throwable
) : DatabaseException(
    "Execution of transaction step $stepIndex failed",
    cause
) {
    override fun toString(): String {
        val nestedError = cause.toString().prependIndent("|   ")

        return """

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
 */
class TransactionException(
    override val cause: Throwable
) : DatabaseException(
    "Execution of transaction failed",
    cause
) {
    override fun toString(): String {
        val nestedError = cause.toString().prependIndent("|   ")

        return """

-------------------------------------
| TRANSACTION FAILED
-------------------------------------
| Error details:
$nestedError
-------------------------------------
"""
    }
}
