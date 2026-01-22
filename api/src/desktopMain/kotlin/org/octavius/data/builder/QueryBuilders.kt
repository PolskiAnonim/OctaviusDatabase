package org.octavius.data.builder

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Defines the public API for building SQL SELECT queries.
 */
interface SelectQueryBuilder: TerminalReturningMethods, QueryBuilder<SelectQueryBuilder> {

    /** Adds a Common Table Expression (CTE) to the query. */
    fun with(name: String, query: String): SelectQueryBuilder

    /** Marks the WITH clause as recursive. */
    fun recursive(): SelectQueryBuilder

    /** Defines the data source (FROM clause). */
    fun from(source: String): SelectQueryBuilder

    /**
     * Uses the result of another query as a data source (derived table).
     * Automatically wraps the subquery in parentheses and adds an alias when provided.
     *
     * @param subquery SQL string containing the subquery.
     * @param alias Name (alias) that will be assigned to the derived table.
     */
    fun fromSubquery(subquery: String, alias: String? = null): SelectQueryBuilder

    /** Defines a filter condition (WHERE clause). */
    fun where(condition: String?): SelectQueryBuilder

    /** Defines row grouping (GROUP BY clause). */
    fun groupBy(columns: String?): SelectQueryBuilder

    /** Filters results after grouping (HAVING clause). */
    fun having(condition: String?): SelectQueryBuilder

    /** Defines result ordering (ORDER BY clause). */
    fun orderBy(ordering: String?): SelectQueryBuilder

    /** Limits the number of returned rows (LIMIT clause). */
    fun limit(count: Long?): SelectQueryBuilder

    /** Specifies the number of rows to skip (OFFSET clause). */
    fun offset(position: Long): SelectQueryBuilder

    /** Configures pagination by setting LIMIT and OFFSET.
     * @param page Page number (zero-indexed).
     * @param size Page size
     */
    fun page(page: Long, size: Long): SelectQueryBuilder
}

/**
 * Defines the public API for building SQL DELETE queries.
 */
interface DeleteQueryBuilder : TerminalReturningMethods, TerminalModificationMethods, QueryBuilder<DeleteQueryBuilder> {

    /** Adds a Common Table Expression (CTE) to the query. */
    fun with(name: String, query: String): DeleteQueryBuilder

    /** Marks the WITH clause as recursive. */
    fun recursive(): DeleteQueryBuilder

    /** Adds a USING clause. */
    fun using(tables: String): DeleteQueryBuilder

    /** Defines the WHERE condition. The clause is mandatory for security reasons. */
    fun where(condition: String): DeleteQueryBuilder

    /**
     * Adds a RETURNING clause. Requires using methods like `.toList()`, `.toSingle()`, etc.
     * instead of `.execute()`.
     */
    fun returning(vararg columns: String): DeleteQueryBuilder
}

/**
 * Defines the public API for building SQL UPDATE queries.
 */
interface UpdateQueryBuilder : TerminalReturningMethods, TerminalModificationMethods, QueryBuilder<UpdateQueryBuilder> {

    /** Adds a Common Table Expression (CTE) to the query. */
    fun with(name: String, query: String): UpdateQueryBuilder

    /** Marks the WITH clause as recursive. */
    fun recursive(): UpdateQueryBuilder

    /**
     * Defines assignments in the SET clause.
     */
    fun setExpressions(values: Map<String, String>): UpdateQueryBuilder

    /** Defines a single assignment in the SET clause. */
    fun setExpression(column: String, value: String): UpdateQueryBuilder

    /** Defines a single assignment in the SET clause. Automatically generates a placeholder with the key name. */
    fun setValue(column: String): UpdateQueryBuilder

    /**
     * Sets values to update. Automatically generates placeholders
     * in ":key" format for each key in the map.
     * Values from the map must be passed in the terminal method (e.g., .execute()).
     */
    fun setValues(values: Map<String, Any?>): UpdateQueryBuilder

    /**
     * Sets values to update. Automatically generates placeholders
     * in ":value" format for each value in the list.
     */
    fun setValues(values: List<String>): UpdateQueryBuilder

    /**
     * Adds a FROM clause to the UPDATE query.
     */
    fun from(tables: String): UpdateQueryBuilder

    /**
     * Defines the WHERE condition. The clause is mandatory for security reasons.
     */
    fun where(condition: String): UpdateQueryBuilder

    /**
     * Adds a RETURNING clause. Requires using methods like `.toList()`, `.toSingle()`, etc.
     * instead of `.execute()`.
     */
    fun returning(vararg columns: String): UpdateQueryBuilder
}

/**
 * Defines the public API for building SQL INSERT queries.
 */
interface InsertQueryBuilder : TerminalReturningMethods, TerminalModificationMethods, QueryBuilder<InsertQueryBuilder> {

    /** Adds a Common Table Expression (CTE) to the query. */
    fun with(name: String, query: String): InsertQueryBuilder

    /** Marks the WITH clause as recursive. */
    fun recursive(): InsertQueryBuilder

    /**
     * Explicitly defines the columns for the INSERT statement.
     *
     * This is optional - if not called, columns will be inferred from [values] or omitted entirely for [fromSelect].
     *
     * @param columns Column names to insert into.
     */
    fun columns(vararg columns: String): InsertQueryBuilder

    /**
     * Defines values to insert as SQL expressions or placeholders.
     * This is a low-level method.
     * @param expressions Map where key is column name and value is SQL string (e.g., ":name", "NOW()").
     */
    fun valuesExpressions(expressions: Map<String, String>): InsertQueryBuilder

    /**
     * Defines a single value to insert as an SQL expression.
     * @param column Column name.
     * @param expression SQL expression (e.g., ":user_id", "DEFAULT").
     */
    fun valueExpression(column: String, expression: String): InsertQueryBuilder

    /**
     * Defines values to insert, automatically generating placeholders.
     * This is the preferred, high-level method for inserting data.
     * Values from the map must be passed in the terminal method (e.g., .execute()).
     *
     * @param data Data map (column -> value).
     */
    fun values(data: Map<String, Any?>): InsertQueryBuilder

    /**
     * Defines values to insert, automatically generating placeholders
     * in ":value" format for each value in the list.
     */
    fun values(values: List<String>): InsertQueryBuilder

    /**
     * Defines a single value, automatically generating a placeholder.
     * @param column Column name for which a placeholder will be generated (e.g., ":column_name").
     */
    fun value(column: String): InsertQueryBuilder

    /**
     * Defines a SELECT query as the data source for insertion.
     *
     * If [columns] was called, generates `INSERT INTO table (cols) SELECT ...`.
     * Otherwise, generates `INSERT INTO table SELECT ...` (columns inferred by database).
     *
     * Mutually exclusive with [values]/[value]/[valueExpression]/[valuesExpressions].
     */
    fun fromSelect(query: String): InsertQueryBuilder

    /**
     * Configures behavior in case of key conflict (ON CONFLICT clause).
     */
    fun onConflict(config: OnConflictClauseBuilder.() -> Unit): InsertQueryBuilder

    /**
     * Adds a RETURNING clause. Requires using `.toList()`, `.toSingle()`, etc. instead of `.execute()`.
     */
    fun returning(vararg columns: String): InsertQueryBuilder
}

/**
 * Configurator for the ON CONFLICT clause in an INSERT query.
 */
interface OnConflictClauseBuilder {

    /** Defines the conflict target as a list of columns. */
    fun onColumns(vararg columns: String)

    /** Defines the conflict target as an existing constraint name. */
    fun onConstraint(constraintName: String)

    /** In case of conflict, do nothing (DO NOTHING). */
    fun doNothing()

    /**
     * In case of conflict, perform an update (DO UPDATE).
     * @param setExpression SET expression, e.g., "counter = tbl.counter + 1". Use `EXCLUDED` to reference the values that were attempted to insert.
     * @param whereCondition Optional WHERE condition for the UPDATE action.
     */
    fun doUpdate(setExpression: String, whereCondition: String? = null)

    /**
     * In case of conflict, perform an update (DO UPDATE) using column-value pairs.
     * This is the preferred way as it's more readable and less error-prone than manually assembling a string.
     * Usage example:
     * doUpdate(
     *   "last_login" to "NOW()",
     *   "login_attempts" to "EXCLUDED.login_attempts + 1"
     * )
     * @param setPairs Pairs (column, expression) to use in the SET clause.
     * @param whereCondition Optional WHERE condition for the UPDATE action.
     */
    fun doUpdate(vararg setPairs: Pair<String, String>, whereCondition: String? = null)

    /**
     * In case of conflict, perform an update (DO UPDATE) using a column-value map.
     * Useful when update logic is built dynamically.
     * @param setMap Map {column -> expression} to use in the SET clause.
     * @param whereCondition Optional WHERE condition for the UPDATE action.
     */
    fun doUpdate(setMap: Map<String, String>, whereCondition: String? = null)
}

/**
 * Defines the public API for passing a complete raw query.
 */
interface RawQueryBuilder : TerminalReturningMethods, TerminalModificationMethods, QueryBuilder<RawQueryBuilder> {
    // Only terminal methods taken from other interfaces
}

interface QueryBuilder<T : QueryBuilder<T>> {
    /**
     * Converts this builder to a StepBuilder that enables lazy execution within a transaction.
     * Returns a wrapper with terminal methods that create TransactionStep instead of executing the query.
     */
    fun asStep(): StepBuilderMethods

    /**
     * Switches the builder to asynchronous mode.
     * Requires providing a CoroutineScope in which operations will be launched.
     *
     * @param scope Coroutine scope (typically from ViewModel/Handler) for lifecycle management.
     * @param ioDispatcher Dispatcher on which the query should be executed
     * @return New builder instance with asynchronous terminal methods.
     */
    fun async(scope: CoroutineScope, ioDispatcher: CoroutineDispatcher = Dispatchers.IO): AsyncTerminalMethods

    /**
     * Switches the builder to streaming mode, optimal for large datasets.
     * Requires using streaming-specific terminal methods like forEachRow().
     * REQUIRES ACTIVE TRANSACTION. This method must be called inside a DataAccess.transaction { ... } block.
     * Otherwise, PostgreSQL will ignore fetchSize and load everything into RAM.
     *
     * @param fetchSize Number of rows fetched from the database in one batch.
     *                  Crucial for avoiding loading the entire result into memory.
     * @return New builder instance with streaming methods.
     */
    fun asStream(fetchSize: Int = 100): StreamingTerminalMethods

    /**
     * Creates and returns a deep copy of this builder.
     * Returns the concrete builder type (e.g., SelectQueryBuilder),
     * maintaining API fluency.
     */
    fun copy(): T


}