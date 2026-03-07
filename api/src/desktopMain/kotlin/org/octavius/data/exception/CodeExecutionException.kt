package org.octavius.data.exception

/**
 * Errors in the application code or framework logic (e.g., mapping failures, dependency errors).
 * Errors that probably can't be fixed without database or code changes
 */
sealed class CodeExecutionException(
    val details: String,
    queryContext: QueryContext?,
    cause: Throwable?
) : DatabaseException("Code execution failed", queryContext, cause)
