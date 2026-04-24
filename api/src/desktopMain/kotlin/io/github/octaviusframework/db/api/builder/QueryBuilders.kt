package io.github.octaviusframework.db.api.builder

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Controls the waiting behavior when acquiring row-level locks in a SELECT query.
 *
 * Used with [SelectQueryBuilder.forUpdate].
 *
 * ```kotlin
 * // Claim a legion slot for assignment — fail immediately if already taken
 * dataAccess.select("*").from("legions")
 *     .where("id = @id AND status = 'AVAILABLE'")
 *     .forUpdate(mode = LockWaitMode.NOWAIT)
 *     .toSingleOf<Legion>("id" to 7)
 *
 * // Process unclaimed tribute records — skip anything another process has locked
 * dataAccess.select("*").from("tribute_records")
 *     .where("status = 'PENDING'")
 *     .forUpdate(mode = LockWaitMode.SKIP_LOCKED)
 *     .limit(10)
 *     .toListOf<TributeRecord>()
 * ```
 */
enum class LockWaitMode {
    /** Fail immediately with an error if any selected row is already locked. */
    NOWAIT,

    /** Skip rows that are already locked instead of waiting for them. */
    SKIP_LOCKED
}

/**
 * Defines the public API for building SQL SELECT queries.
 *
 * ```kotlin
 * dataAccess.select("l.id", "l.name", "p.name AS province_name")
 *     .from("legions l JOIN provinces p ON l.province_id = p.id")
 *     .where("l.status = @status")
 *     .orderBy("l.name ASC")
 *     .page(0, 20)
 *     .toListOf<LegionSummary>("status" to LegionStatus.OnMarch)
 * ```
 */
interface SelectQueryBuilder : TerminalReturningMethods, QueryBuilder<SelectQueryBuilder> {

    /** Adds a Common Table Expression (CTE) to the query. */
    fun with(name: String, query: String): SelectQueryBuilder

    /** Marks the WITH clause as recursive. */
    fun recursive(): SelectQueryBuilder

    /**
     * Defines the data source (FROM clause).
     *
     * The programmer is fully responsible for passing correct syntax.
     * The method does not perform any formatting or wrapping in parentheses.
     *
     * Examples of valid values:
     * - `"legions"`
     * - `"legions l"`
     * - `"legions l JOIN provinces p ON l.province_id = p.id"`
     * - `"(SELECT id FROM active_legions) AS l"`
     * - `"UNNEST(@ids) AS id"`
     */
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

    /**
     * Configures pagination by setting LIMIT and OFFSET.
     * @param page Page number (zero-indexed).
     * @param size Page size.
     */
    fun page(page: Long, size: Long): SelectQueryBuilder

    /**
     * Appends a `FOR UPDATE` locking clause to the query.
     *
     * Locks selected rows for the duration of the current transaction,
     * preventing concurrent modification.
     *
     * @param of Optional comma-separated list of table names to lock (e.g., `"legions, provinces"`).
     *           If `null`, all tables referenced in the query are locked.
     * @param mode Optional wait strategy: [LockWaitMode.NOWAIT] raises an error immediately
     *             if any row is locked; [LockWaitMode.SKIP_LOCKED] silently skips locked rows.
     *             If `null`, the query waits until the lock is acquired.
     */
    fun forUpdate(of: String? = null, mode: LockWaitMode? = null): SelectQueryBuilder
}

/**
 * Defines the public API for building SQL DELETE queries.
 *
 * ```kotlin
 * // Discharge a legionnaire from service
 * dataAccess.deleteFrom("legionnaires")
 *     .where("id = @id AND status = 'DISCHARGED'")
 *     .returning("name", "legion_id")
 *     .toSingleOf<DischargeRecord>("id" to legionnaireId)
 * ```
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
 *
 * ```kotlin
 * // Promote a legionnaire and record the timestamp
 * dataAccess.update("legionnaires")
 *     .setValue("rank")
 *     .setExpression("promoted_at", "NOW()")
 *     .where("id = @id")
 *     .returning("*")
 *     .toSingleOf<Legionnaire>("rank" to Rank.Optio, "id" to legionnaireId)
 * ```
 */
interface UpdateQueryBuilder : TerminalReturningMethods, TerminalModificationMethods, QueryBuilder<UpdateQueryBuilder> {

    /** Adds a Common Table Expression (CTE) to the query. */
    fun with(name: String, query: String): UpdateQueryBuilder

    /** Marks the WITH clause as recursive. */
    fun recursive(): UpdateQueryBuilder

    /** Defines assignments in the SET clause using raw SQL expressions. */
    fun setExpressions(values: Map<String, String>): UpdateQueryBuilder

    /** Defines a single assignment in the SET clause using a raw SQL expression. */
    fun setExpression(column: String, value: String): UpdateQueryBuilder

    /** Defines a single assignment in the SET clause. Automatically generates a placeholder with the key name. */
    fun setValue(column: String): UpdateQueryBuilder

    /**
     * Sets values to update. Automatically generates placeholders
     * in `@key` format for each key in the map.
     * Values from the map must be passed in the terminal method (e.g., `.execute()`).
     */
    fun setValues(values: Map<String, Any?>): UpdateQueryBuilder

    /**
     * Sets values to update. Automatically generates placeholders
     * in `@value` format for each value in the list.
     */
    fun setValues(values: List<String>): UpdateQueryBuilder

    /** Adds a FROM clause to the UPDATE query. */
    fun from(tables: String): UpdateQueryBuilder

    /** Defines the WHERE condition. The clause is mandatory for security reasons. */
    fun where(condition: String): UpdateQueryBuilder

    /**
     * Adds a RETURNING clause. Requires using methods like `.toList()`, `.toSingle()`, etc.
     * instead of `.execute()`.
     */
    fun returning(vararg columns: String): UpdateQueryBuilder
}

/**
 * Defines the public API for building SQL INSERT queries.
 *
 * ```kotlin
 * // Enlist a new legionnaire, returning the generated ID
 * val data = legionnaire.toDataMap("id")
 * dataAccess.insertInto("legionnaires")
 *     .values(data)
 *     .valueExpression("enlisted_at", "NOW()")
 *     .returning("id")
 *     .toField<Int>(data)
 * ```
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
     * @param expressions Map where key is column name and value is a SQL expression (e.g., `"@name"`, `"NOW()"`).
     */
    fun valuesExpressions(expressions: Map<String, String>): InsertQueryBuilder

    /**
     * Defines a single value to insert as a SQL expression.
     * @param column Column name.
     * @param expression SQL expression (e.g., `"@legion_id"`, `"DEFAULT"`).
     */
    fun valueExpression(column: String, expression: String): InsertQueryBuilder

    /**
     * Defines values to insert, automatically generating placeholders.
     * This is the preferred, high-level method for inserting data.
     * Values from the map must be passed in the terminal method (e.g., `.execute()`).
     *
     * @param data Data map (column -> value).
     */
    fun values(data: Map<String, Any?>): InsertQueryBuilder

    /**
     * Defines values to insert, automatically generating placeholders
     * in `@value` format for each value in the list.
     */
    fun values(values: List<String>): InsertQueryBuilder

    /**
     * Defines a single value, automatically generating a placeholder.
     * @param column Column name for which a placeholder will be generated (e.g., `@column_name`).
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
     * Configures behavior in case of a key conflict (ON CONFLICT clause).
     *
     * ```kotlin
     * // Re-enlist a legionnaire — update their status if they already exist
     * dataAccess.insertInto("legionnaires")
     *     .values(data)
     *     .onConflict {
     *         onColumns("name", "legion_id")
     *         doUpdate(
     *             "status" to "EXCLUDED.status",
     *             "re_enlisted_at" to "NOW()"
     *         )
     *     }
     *     .execute(data)
     * ```
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
     * @param setExpression SET expression. Use `EXCLUDED` to reference the values that were attempted to insert.
     *                      E.g., `"tribute_amount = EXCLUDED.tribute_amount"`.
     * @param whereCondition Optional WHERE condition for the UPDATE action.
     */
    fun doUpdate(setExpression: String, whereCondition: String? = null)

    /**
     * In case of conflict, perform an update (DO UPDATE) using column-value pairs.
     * Preferred over the string overload — more readable and less error-prone.
     *
     * ```kotlin
     * doUpdate(
     *     "tribute_amount" to "EXCLUDED.tribute_amount",
     *     "last_collected_at" to "NOW()"
     * )
     * ```
     * @param setPairs Pairs (column, expression) to use in the SET clause.
     * @param whereCondition Optional WHERE condition for the UPDATE action.
     */
    fun doUpdate(vararg setPairs: Pair<String, String>, whereCondition: String? = null)

    /**
     * In case of conflict, perform an update (DO UPDATE) using a column-value map.
     * Useful when update logic is built dynamically.
     * @param setMap Map `{column -> expression}` to use in the SET clause.
     * @param whereCondition Optional WHERE condition for the UPDATE action.
     */
    fun doUpdate(setMap: Map<String, String>, whereCondition: String? = null)
}

/**
 * Defines the public API for passing a complete raw SQL query.
 *
 * ```kotlin
 * // Call a PostgreSQL function directly
 * dataAccess.rawQuery("SELECT * FROM calculate_tribute(@province, @year)")
 *     .toField<Int>("province" to "Aegyptus", "year" to 44)
 * ```
 */
interface RawQueryBuilder : TerminalReturningMethods, TerminalModificationMethods, QueryBuilder<RawQueryBuilder>


// ==================== QueryBuilder ====================

interface QueryBuilder<T : QueryBuilder<T>> {
    /**
     * Converts this builder to a [StepBuilderMethods] for lazy execution within a [TransactionPlan][io.github.octaviusframework.db.api.transaction.TransactionPlan].
     *
     * ```kotlin
     * val plan = TransactionPlan()
     * val legionHandle = plan.add(
     *     dataAccess.insertInto("legions").values(legionData).asStep().toField<Int>()
     * )
     * ```
     */
    fun asStep(): StepBuilderMethods

    /**
     * Switches the builder to asynchronous mode.
     * Requires providing a [CoroutineScope] in which callback will be launched.
     *
     * @param scope Coroutine scope (typically from ViewModel or request handler) for lifecycle management.
     * @param ioDispatcher Dispatcher on which the query should be executed.
     * @return New builder instance with asynchronous terminal methods.
     */
    fun async(scope: CoroutineScope, ioDispatcher: CoroutineDispatcher = Dispatchers.IO): AsyncTerminalMethods

    /**
     * Switches the builder to streaming mode, optimal for large datasets.
     *
     * **Requires an active transaction.** Must be called inside a `DataAccess.transaction { }` block —
     * otherwise PostgreSQL will ignore [fetchSize] and load all rows into RAM.
     *
     * ```kotlin
     * dataAccess.transaction { tx ->
     *     tx.select("*").from("census_records")
     *         .where("year = @year")
     *         .asStream(fetchSize = 500)
     *         .forEachRow<CensusRecord>("year" to 14) { record ->
     *             processCensusEntry(record)
     *         }
     * }
     * ```
     *
     * @param fetchSize Number of rows fetched from the database in one batch.
     * @return New builder instance with streaming terminal methods.
     */
    fun asStream(fetchSize: Int = 100): StreamingTerminalMethods

    /**
     * Creates and returns a deep copy of this builder.
     * Useful for creating query variants from a shared base without modifying the original.
     *
     * ```kotlin
     * val base = dataAccess.select("*").from("legions").orderBy("name ASC")
     * val active = base.copy().where("status = 'ON_MARCH'")
     * val garrisoned = base.copy().where("status = 'GARRISONED'")
     * ```
     */
    fun copy(): T
}
