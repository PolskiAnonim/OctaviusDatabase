package org.octavius.database.exception

import org.octavius.data.exception.*
import org.springframework.dao.*
import org.springframework.jdbc.BadSqlGrammarException
import java.sql.SQLException

object ExceptionTranslator {

    fun translate(ex: Throwable, queryContext: QueryContext): DatabaseException {
        return when (ex) {
            is StepDependencyException -> ex // Context added inside TransactionPlanExecutor
            // CodeExecutionException (RuntimeTypeRegistryException and ConversionException) must be given context
            is CodeExecutionException -> ex.withContext(queryContext)
            // StepDependencyException!!!
            is DatabaseException -> ex
            is DataAccessException -> translateSpringException(ex, queryContext)
            else -> DatabaseException.DatabaseExecutionException(
                errorType = DbErrorType.UNKNOWN,
                queryContext = queryContext,
                cause = ex
            )
        }
    }

    private fun translateSpringException(ex: DataAccessException, queryContext: QueryContext): DatabaseException {
        return when (ex) {
            is DuplicateKeyException -> DatabaseException.DatabaseExecutionException(
                errorType = DbErrorType.UNIQUE_CONSTRAINT_VIOLATION,
                constraintName = extractConstraintName(ex),
                queryContext = queryContext,
                cause = ex
            )
            is QueryTimeoutException, is TransientDataAccessException -> DatabaseException.ConcurrencyException(
                errorType = ConcurrencyErrorType.TIMEOUT,
                queryContext = queryContext,
                cause = ex
            )
            is PessimisticLockingFailureException -> DatabaseException.ConcurrencyException(
                errorType = ConcurrencyErrorType.DEADLOCK,
                queryContext = queryContext,
                cause = ex
            )
            is BadSqlGrammarException -> DatabaseException.DatabaseExecutionException(
                errorType = DbErrorType.BAD_SQL_GRAMMAR,
                queryContext = queryContext,
                cause = ex
            )
            is DataIntegrityViolationException -> {
                val sqlException = findSqlException(ex)
                val (type, constraint) = parsePostgresError(sqlException)
                DatabaseException.DatabaseExecutionException(
                    errorType = type,
                    constraintName = constraint ?: extractConstraintName(ex),
                    queryContext = queryContext,
                    cause = ex
                )
            }
            is DataAccessResourceFailureException -> DatabaseException.ConnectionException(
                message = "Database connection failed",
                cause = ex
            )
            else -> DatabaseException.DatabaseExecutionException(DbErrorType.UNKNOWN, null, queryContext, ex)
        }
    }

    private fun extractConstraintName(ex: DataAccessException): String? {
        // Very basic extraction from message for PostgreSQL
        // Usually looks like: ... constraint "unique_name" ...
        val message = ex.mostSpecificCause.message ?: return null
        val regex = "constraint \"([^\"]+)\"".toRegex()
        return regex.find(message)?.groupValues?.get(1)
    }

    private fun findSqlException(ex: Throwable): SQLException? {
        var cause: Throwable? = ex
        while (cause != null) {
            if (cause is SQLException) return cause
            cause = cause.cause
        }
        return null
    }

    private fun parsePostgresError(sqlEx: SQLException?): Pair<DbErrorType, String?> {
        if (sqlEx == null) return DbErrorType.DATA_INTEGRITY to null
        
        return when (sqlEx.sqlState) {
            "23505" -> DbErrorType.UNIQUE_CONSTRAINT_VIOLATION to null
            "23503" -> DbErrorType.FOREIGN_KEY_VIOLATION to null
            "23502" -> DbErrorType.NOT_NULL_VIOLATION to null
            "23514" -> DbErrorType.CHECK_CONSTRAINT_VIOLATION to null
            else -> DbErrorType.DATA_INTEGRITY to null
        }
    }
}
