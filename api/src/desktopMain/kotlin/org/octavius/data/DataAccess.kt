package org.octavius.data

import org.octavius.data.builder.*
import org.octavius.data.notification.PgChannelListener
import org.octavius.data.transaction.TransactionPlan
import org.octavius.data.transaction.TransactionPlanResult
import org.octavius.data.transaction.TransactionPropagation

/**
 * Defines the contract for basic database operations (CRUD and raw queries).
 *
 * This interface provides the foundation that is used both for executing
 * single queries and operations within a transaction block.
 */
interface QueryOperations {

    /**
     * Starts building a SELECT query.
     *
     * @param columns List of columns to fetch. At least one must be provided.
     * @return New builder instance for a SELECT query.
     */
    fun select(vararg columns: String): SelectQueryBuilder

    /**
     * Starts building an UPDATE query.
     *
     * @param table Name of the table to update.
     * @return New builder instance for an UPDATE query.
     */
    fun update(table: String): UpdateQueryBuilder

    /**
     * Starts building an INSERT query.
     *
     * @param table Name of the table into which data is being inserted.
     * @return New builder instance for an INSERT query.
     */
    fun insertInto(table: String): InsertQueryBuilder

    /**
     * Starts building a DELETE query.
     *
     * @param table Name of the table from which data is being deleted.
     * @return New builder instance for a DELETE query.
     */
    fun deleteFrom(table: String): DeleteQueryBuilder

    /**
     * Enables execution of a raw SQL query.
     *
     * @param sql SQL query to execute, may contain named parameters (e.g., `:userId`).
     * @return New builder instance for a raw query.
     */
    fun rawQuery(sql: String): RawQueryBuilder
}

/**
 * Main entry point to the data layer, offering a consistent API for database interaction.
 *
 * This facade enables:
 * 1. Executing single queries (CRUD) in auto-commit mode, through [QueryOperations] implementation.
 * 2. Executing atomic, complex operations within managed transaction blocks.
 * 3. Running predefined, declarative transaction plans.
 */
interface DataAccess : QueryOperations {

    /**
     * Executes a sequence of operations (plan) within a single, atomic transaction.
     *
     * Ideal solution for scenarios where transaction steps are built dynamically,
     * e.g., based on form data.
     *
     * @param plan Transaction plan to execute.
     * @param propagation Defines transaction behavior (e.g., whether to join existing or create new).
     * @return [DataResult] containing [TransactionPlanResult] on success or error.
     */
    fun executeTransactionPlan(
        plan: TransactionPlan,
        propagation: TransactionPropagation = TransactionPropagation.REQUIRED
    ): DataResult<TransactionPlanResult>

    /**
     * Executes the given block of code within a new, managed transaction.
     *
     * Ensures that all operations inside the `block` are executed atomically.
     * The transaction will be committed only if the block completes successfully
     * and returns [DataResult.Success]. In any other case (returning [DataResult.Failure]
     * or throwing an exception), the transaction will be automatically rolled back.
     *
     * @param propagation Defines transaction behavior (e.g., whether to join existing or create new).
     * @param block Lambda that receives a [QueryOperations] context for performing operations.
     * @return [DataResult] with the result of the block operation (`T`) or error.
     */
    fun <T> transaction(
        propagation: TransactionPropagation = TransactionPropagation.REQUIRED,
        block: (tx: QueryOperations) -> DataResult<T>
    ): DataResult<T>

    /**
     * Sends a notification to the given PostgreSQL channel via `pg_notify`.
     *
     * Can be used both inside and outside of a transaction. If used inside a transaction,
     * the notification is only delivered to listeners after the transaction commits.
     *
     * @param channel Name of the channel to send the notification to.
     * @param payload Optional payload string (max 8000 bytes). `null` sends no payload.
     * @return [DataResult.Success] on success, or [DataResult.Failure] on error.
     */
    fun notify(channel: String, payload: String? = null): DataResult<Unit>

    /**
     * Creates a new [PgChannelListener] backed by a dedicated database connection.
     *
     * The returned listener holds an open connection until [PgChannelListener.close] is called.
     * Always release the listener via [kotlin.io.use] or an explicit [PgChannelListener.close] call.
     *
     * @return A new [PgChannelListener] ready to subscribe to channels.
     */
    fun createChannelListener(): PgChannelListener
}