package org.octavius.database.exception

import org.octavius.data.exception.*
import org.postgresql.util.PSQLException
import java.sql.SQLException

object ExceptionTranslator {

    /**
     * Translates any [Throwable] into an Octavius [DatabaseException].
     * Prioritizes [SQLException] and its PostgreSQL-specific error codes (SQLSTATE).
     */
    fun translate(ex: Throwable, queryContext: QueryContext): DatabaseException {
        // Already a domain exception, just pass through (possibly enrich with context)
        when (ex) {
            is StepDependencyException -> return ex // Context added inside TransactionPlanExecutor
            // (TypeRegistryException and ConversionException) must be given context
            is TypeRegistryException, is ConversionException -> return ex.withContext(queryContext)
            // InitializationException -> only on start - impossible here, ConstraintViolationException -> created here
            // GrammarException -> created here, PermissionException -> created here, ConnectionException -> only on start - impossible here
            // ConcurrencyException -> created here, UnknownDatabaseException -> created here
        }

        // If it's a wrapper, find the underlying SQLException
        val sqlException = findSqlException(ex)
        if (sqlException != null) {
            return translateSqlException(sqlException, queryContext)
        }

        // Fallback for non-SQL exceptions
        return UnknownDatabaseException(message = ex.message ?: "An unexpected error occurred", cause = ex)
            .withContext(queryContext)
    }

    private fun findSqlException(ex: Throwable): SQLException? {
        var cause: Throwable? = ex
        while (cause != null) {
            if (cause is SQLException) return cause
            // Spring DataAccessException often wraps SQLException
            cause = cause.cause
        }
        return null
    }

    /**
     * Main translation logic based on PostgreSQL SQLSTATE codes.
     */
    private fun translateSqlException(sqlEx: SQLException, queryContext: QueryContext): DatabaseException {
        val state = sqlEx.sqlState ?: ""
        val pgMetadata = extractPostgresMetadata(sqlEx)

        return when {
            // Class 08 — Connection Exception
            state.startsWith("08") -> ConnectionException(sqlEx.message ?: "Connection error", sqlEx)

            // Class 22 — Data Exception (Invalid data provided by the user) TODO Should it be another exception not Constraint violation? Or should ConstraintViolationException have changed name?
            state.startsWith("22") -> ConstraintViolationException(
                messageEnum = ConstraintViolationExceptionMessage.DATA_INTEGRITY,
                tableName = pgMetadata.table,
                columnName = pgMetadata.column,
                constraintName = pgMetadata.constraint,
                queryContext = queryContext,
                cause = sqlEx
            )

            // Class 23 — Integrity Constraint Violation
            state.startsWith("23") -> {
                val messageEnum = when (state) {
                    "23502" -> ConstraintViolationExceptionMessage.NOT_NULL_VIOLATION
                    "23503" -> ConstraintViolationExceptionMessage.FOREIGN_KEY_VIOLATION
                    "23505" -> ConstraintViolationExceptionMessage.UNIQUE_CONSTRAINT_VIOLATION
                    "23514" -> ConstraintViolationExceptionMessage.CHECK_CONSTRAINT_VIOLATION
                    else -> ConstraintViolationExceptionMessage.DATA_INTEGRITY
                }
                ConstraintViolationException(
                    messageEnum = messageEnum,
                    tableName = pgMetadata.table,
                    columnName = pgMetadata.column,
                    constraintName = pgMetadata.constraint,
                    queryContext = queryContext,
                    cause = sqlEx
                )
            }

            // Class 40 — Transaction Rollback (Serialization failures and deadlocks)
            state.startsWith("40") -> {
                val errorType = when (state) {
                    "40P01" -> ConcurrencyErrorType.DEADLOCK
                    else -> ConcurrencyErrorType.TIMEOUT // or serialization failure, usually retriable
                }
                ConcurrencyException(errorType, queryContext, sqlEx)
            }

            // Class 42 — Syntax Error or Access Rule Violation (Grammar or non-existent objects)
            state.startsWith("42") -> {
                if (state == "42501") {
                    PermissionException(sqlEx.message ?: "Insufficient privilege", queryContext, sqlEx)
                } else {
                    GrammarException(sqlEx.message ?: "SQL Grammar error", queryContext, sqlEx)
                }
            }

            // Class 28 — Invalid Authorization Specification
            state.startsWith("28") -> PermissionException(sqlEx.message ?: "Invalid authorization", queryContext, sqlEx)

            // Class 57 — Operator Intervention (Query canceled, shutdown, etc.)
            state == "57014" -> ConcurrencyException(ConcurrencyErrorType.TIMEOUT, queryContext, sqlEx)
            state.startsWith("57") -> ConnectionException("Database operator intervention: ${sqlEx.message}", sqlEx)

            // Class 53/54 - Insufficient Resources / Program Limit Exceeded
            state.startsWith("53") || state.startsWith("54") -> ConnectionException("Database resources exceeded: ${sqlEx.message}", sqlEx)

            // Class 55 — Object Not In Prerequisite State
            state.startsWith("55") -> ConnectionException("Database object state error: ${sqlEx.message}", sqlEx)

            // Class 58 — System Error (errors external to PostgreSQL itself)
            state.startsWith("58") -> ConnectionException("System error: ${sqlEx.message}", sqlEx)

            // Class P0 — PL/pgSQL Error
            state.startsWith("P0") -> UnknownDatabaseException("PL/pgSQL Error: ${sqlEx.message}", sqlEx).withContext(queryContext)

            // Class XX — Internal Error
            state.startsWith("XX") -> ConnectionException("Internal database error: ${sqlEx.message}", sqlEx)

            else -> UnknownDatabaseException(sqlEx.message ?: "Unknown SQL Error (State: $state)", sqlEx).withContext(queryContext)
        }
    }

    private fun extractPostgresMetadata(sqlEx: SQLException?): PostgresErrorMetadata {
        if (sqlEx is PSQLException) {
            val serverError = sqlEx.serverErrorMessage
            if (serverError != null) {
                return PostgresErrorMetadata(
                    table = serverError.table,
                    column = serverError.column,
                    constraint = serverError.constraint,
                    detail = serverError.detail
                )
            }
        }
        return PostgresErrorMetadata()
    }

    private data class PostgresErrorMetadata(
        val table: String? = null,
        val column: String? = null,
        val constraint: String? = null,
        val detail: String? = null
    )
}
