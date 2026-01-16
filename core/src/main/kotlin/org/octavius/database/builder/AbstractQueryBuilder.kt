package org.octavius.database.builder

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.octavius.data.DataResult
import org.octavius.data.builder.AsyncTerminalMethods
import org.octavius.data.builder.QueryBuilder
import org.octavius.data.builder.StepBuilderMethods
import org.octavius.data.builder.StreamingTerminalMethods
import org.octavius.data.exception.QueryExecutionException
import org.octavius.database.RowMappers
import org.octavius.database.type.KotlinToPostgresConverter
import org.octavius.database.type.PositionalQuery
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Base class for all builders that can return results as data rows
 * (via `SELECT` or `RETURNING` clause).
 *
 * Unifies the API for terminal methods (`toList`, `toSingle`, `toField`, etc.) and the logic
 * for building queries with WITH clauses (Common Table Expressions). Uses generics to provide
 * a fluent interface in subclasses.
 */
internal abstract class AbstractQueryBuilder<R : QueryBuilder<R>>(
    val jdbcTemplate: JdbcTemplate,
    val kotlinToPostgresConverter: KotlinToPostgresConverter,
    val rowMappers: RowMappers,
    protected val table: String? = null,
): QueryBuilder<R> {
    companion object {
        private val logger = KotlinLogging.logger {}
    }
    // We really don't want SELECT to die when executing queries
    protected abstract val canReturnResultsByDefault: Boolean
    //------------------------------------------------------------------------------------------------------------------
    //                                 ABSTRACT METHOD TO IMPLEMENT
    //------------------------------------------------------------------------------------------------------------------

    /**
     * Builds the final SQL query based on the builder's state.
     * Must be implemented by each concrete builder class.
     */
    abstract fun buildSql(): String

    //------------------------------------------------------------------------------------------------------------------
    //                                         RETURNING CLAUSE (for INSERT/UPDATE/DELETE)
    //------------------------------------------------------------------------------------------------------------------
    protected var returningClause: String? = null

    /**
     * Adds a RETURNING clause to the modifying query (INSERT, UPDATE, DELETE).
     * @param columns Columns to return after executing the operation.
     */
    @Suppress("UNCHECKED_CAST")
    fun returning(vararg columns: String): R = apply {
        this.returningClause = columns.joinToString(", ")
    } as R

    /**
     * Builds the SQL fragment for the RETURNING clause.
     */
    protected fun buildReturningClause(): String {
        return returningClause?.let { "\nRETURNING $it" } ?: ""
    }

    //------------------------------------------------------------------------------------------------------------------
    //                                              CTE (WITH)
    //------------------------------------------------------------------------------------------------------------------
    protected val withClauses: MutableList<Pair<String, String>> = mutableListOf()
    protected var recursiveWith: Boolean = false


    /**
     * Adds a query to the WITH clause (Common Table Expression).
     * @param name Name (alias) for the CTE.
     * @param query SQL query defining the CTE.
     */
    @Suppress("UNCHECKED_CAST")
    fun with(name: String, query: String): R = apply {
        withClauses.add(name to query)
    } as R

    /**
     * Marks the WITH clause as recursive.
     */
    @Suppress("UNCHECKED_CAST")
    fun recursive(): R = apply {
        this.recursiveWith = true
    } as R

    /**
     * Creates a formatted SQL fragment for the WITH clause based on added queries.
     * Each CTE is on a new line for readability.
     */
    protected fun buildWithClause(): String {
        if (withClauses.isEmpty()) return ""
        val sb = StringBuilder("WITH ")
        if (recursiveWith) {
            sb.append("RECURSIVE ")
        }
        // Each CTE on a new line with indentation
        sb.append(withClauses.joinToString(",\n  ") { "${it.first} AS (${it.second})" })
        // New line separating WITH from main query
        sb.append("\n")
        return sb.toString()
    }

    //------------------------------------------------------------------------------------------------------------------
    //                                            TERMINAL METHODS
    //------------------------------------------------------------------------------------------------------------------

    // --- Mapping to Map<String, Any?> ---

    /** Executes the query and returns a list of rows as `List<Map<String, Any?>>`. */
    fun toList(params: Map<String, Any?>): DataResult<List<Map<String, Any?>>> {
        return executeReturningQuery(params, rowMappers.ColumnNameMapper()) { DataResult.Success(it) }
    }

    /** Executes the query and returns a single row as `Map<String, Any?>?`. */
    fun toSingle(params: Map<String, Any?>): DataResult<Map<String, Any?>?> {
        return executeReturningQuery(params, rowMappers.ColumnNameMapper()) { DataResult.Success(it.firstOrNull()) }
    }

    // --- Mapping to objects based on KClass ---

    /** Executes the query and maps results to a list of objects of the given class. */
    fun <T : Any> toListOf(kClass: KClass<T>, params: Map<String, Any?>): DataResult<List<T>> {
        return executeReturningQuery(params, rowMappers.DataObjectMapper(kClass)) { DataResult.Success(it) }
    }

    /** Executes the query and maps the result to a single object of the given class. */
    fun <T : Any> toSingleOf(kClass: KClass<T>, params: Map<String, Any?>): DataResult<T?> {
        return executeReturningQuery(
            params,
            rowMappers.DataObjectMapper(kClass)
        ) { DataResult.Success(it.firstOrNull()) }
    }

    // --- Mapping to single values (scalar) ---

    /** Executes the query and returns the value from the first column of the first row. */
    fun <T: Any> toField(targetType: KType, params: Map<String, Any?>): DataResult<T?> {
        return executeReturningQuery(params, rowMappers.SingleValueMapper<T>(targetType)) {
            DataResult.Success(it.firstOrNull())
        }
    }

    /** Executes the query and returns a list of values from the first column of all rows. */
    fun <T: Any> toColumn(targetType: KType, params: Map<String, Any?>): DataResult<List<T?>> {
        return executeReturningQuery(params, rowMappers.SingleValueMapper<T>(targetType)) {
            DataResult.Success(it)
        }
    }

    /** Returns the generated SQL string without executing the query. */
    fun toSql(): String {
        return buildSql()
    }

    override fun toString(): String {
        return toSql()
    }

    /**
     * Executes a modifying query (without RETURNING) and returns the number of affected rows.
     * Throws an exception if a RETURNING clause was used - in that case, use
     * `toList()`, `toSingle()`, etc. methods instead.
     */
    fun execute(params: Map<String, Any?>): DataResult<Int> {
        check(returningClause == null) { "Use toList(), toSingle(), etc. methods when RETURNING clause is defined." }
        val sql = buildSql()
        return execute(sql, params) { positionalSql, positionalParams ->
            val affectedRows = jdbcTemplate.update(positionalSql, *positionalParams.toTypedArray())
            DataResult.Success(affectedRows)
        }
    }

    //------------------------------------------------------------------------------------------------------------------
    //                                          QUERY EXECUTION
    //------------------------------------------------------------------------------------------------------------------

    /**
     * Private helper method for executing queries that return rows.
     * @param params Query parameters.
     * @param rowMapper Method for mapping a single row.
     * @param transform Function that transforms the mapped result list into the final [DataResult].
     */
    private fun <R, M> executeReturningQuery(
        params: Map<String, Any?>,
        rowMapper: RowMapper<M>,
        transform: (List<M>) -> DataResult<R>
    ): DataResult<R> {
        check(canReturnResultsByDefault || returningClause != null) { "Cannot call toList(), toSingle(), etc. on a modifying query without RETURNING clause. Use .returning()." }
        val sql = buildSql()
        return execute(sql, params) { positionalSql, positionalParams ->
            val results: List<M> = jdbcTemplate.query(positionalSql, rowMapper, *positionalParams.toTypedArray())
            transform(results)
        }
    }

    //  ---MAIN QUERY EXECUTION METHOD---

    /**
     * Generic function for executing queries, wrapping logic in error handling,
     * type conversion, and logging.
     *
     * @param sql SQL query to execute.
     * @param params Parameter map.
     * @param action Lambda that will be executed with the prepared query and parameters.
     * @return Operation result as [DataResult].
     */
    protected fun <R> execute(
        sql: String,
        params: Map<String, Any?>,
        action: (positionalSql: String, positionalParams: List<Any?>) -> DataResult<R>
    ): DataResult<R> {
        var positionalQuery: PositionalQuery? = null
        return try {
            positionalQuery = kotlinToPostgresConverter.expandParametersInQuery(sql, params)
            logger.debug {
                """
                Executing query (original): $sql with params: $params
                  -> (expanded): ${positionalQuery.sql} with positional params: ${positionalQuery.params}
                """.trimIndent()
            }
            action(positionalQuery.sql, positionalQuery.params)
        } catch (e: Exception) {
            val executionException = QueryExecutionException(
                sql = sql,
                params = params,
                expandedSql = positionalQuery?.sql,
                expandedParams = positionalQuery?.params,
                cause = e
            )

            logger.error(executionException) { "Database error occurred" }

            DataResult.Failure(executionException)
        }
    }

    //------------------------------------------------------------------------------------------------------------------
    //                                          BUILDER CONVERSION
    //------------------------------------------------------------------------------------------------------------------

    /**
     * Converts this builder to a StepBuilder, which enables lazy execution within a transaction.
     * Returns a wrapper with terminal methods that create TransactionStep instead of executing the query.
     */
    override fun asStep(): StepBuilderMethods {
        @Suppress("UNCHECKED_CAST")
        return StepBuilder(this)
    }


    override fun async(scope: CoroutineScope, ioDispatcher: CoroutineDispatcher): AsyncTerminalMethods {
        return AsyncQueryBuilder(this, scope, ioDispatcher)
    }

    override fun asStream(fetchSize: Int): StreamingTerminalMethods {
        return StreamingQueryBuilder(this, fetchSize)
    }

    //------------------------------------------------------------------------------------------------------------------
    //                                          BUILDER COPY
    //------------------------------------------------------------------------------------------------------------------


    /**
     * Copies state from another builder of the same type.
     * Used by `copy()` methods in derived classes.
     */
    protected fun copyBaseStateFrom(source: AbstractQueryBuilder<R>) {
        this.returningClause = source.returningClause
        this.withClauses.clear()
        this.withClauses.addAll(source.withClauses)
        this.recursiveWith = source.recursiveWith
    }

    abstract override fun copy(): R
}
