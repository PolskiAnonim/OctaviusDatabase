package org.octavius.data.exception

/**
 * Base sealed exception for most Octavius Database errors.
 * Only InitializationException and BuilderException are excluded as they are thrown and can't be inside DataResult
 */
sealed class DatabaseException(
    message: String,
    cause: Throwable? = null,
    queryContext: QueryContext? = null,
    private val includeCauseInToString: Boolean = true
): RuntimeException(message, cause) {

    private var _queryContext: QueryContext? = queryContext
    open val queryContext: QueryContext? get() = _queryContext

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

    /**
     * Subclasses can provide additional technical details here.
     */
    open fun getDetailedMessage(): String? = null

    override fun toString(): String {
        val contextStr = queryContext?.toString() ?: ""
        val detailedMsg = getDetailedMessage()?.let { "DETAILS: $it\n" } ?: ""
        
        val causeSection = if (includeCauseInToString) {
            val nestedError = cause?.toString() ?: "No cause available"
            """
CAUSE:
------------------------------------------------------------
$nestedError
------------------------------------------------------------
"""
        } else ""

        return """
$contextStr

------------------------------------------------------------
ERROR: ${this::class.simpleName}
MESSAGE: $message
${detailedMsg}------------------------------------------------------------
$causeSection
"""
    }
}

/**
 * Infrastructure and connectivity issues.
 */
class ConnectionException(
    message: String,
    queryContext: QueryContext? = null,
    cause: Throwable?
) : DatabaseException(message, cause, queryContext, includeCauseInToString = true)

/**
 * Concurrency and transaction-related issues (e.g., deadlocks, timeouts).
 */
class ConcurrencyException(
    val errorType: ConcurrencyErrorType,
    queryContext: QueryContext?,
    cause: Throwable?
) : DatabaseException("Concurrency error: $errorType", cause, queryContext, includeCauseInToString = false)

enum class ConcurrencyErrorType {
    TIMEOUT,
    DEADLOCK,
    SERIALIZATION_FAILURE
}

class UnknownDatabaseException(
    message: String,
    queryContext: QueryContext? = null,
    cause: Throwable?,
) : DatabaseException(message, cause, queryContext = queryContext, includeCauseInToString = true)
