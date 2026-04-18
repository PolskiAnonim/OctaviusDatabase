package org.octavius.data

import org.octavius.data.builder.*
import org.octavius.data.notification.PgChannelListener
import org.octavius.data.transaction.IsolationLevel
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
     * @param sql SQL query to execute, may contain named parameters (e.g., `@userId`).
     * @return New builder instance for a raw query.
     */
    fun rawQuery(sql: String): RawQueryBuilder
}

/**
 * Main entry point to the data access layer, providing a unified API for interacting with PostgreSQL.
 *
 * This facade orchestrates different interaction patterns:
 * 1. **Direct Queries:** Execution of single SELECT, INSERT, UPDATE, or DELETE operations in auto-commit mode.
 * 2. **Managed Transactions:** Atomic execution of complex logic blocks where multiple operations must succeed or fail together.
 * 3. **Declarative Transaction Plans:** Execution of pre-built sequences of operations ([TransactionPlan]) that can handle step dependencies.
 * 4. **Pub/Sub Messaging:** Native integration with PostgreSQL LISTEN/NOTIFY mechanism for real-time updates.
 *
 * Implementation is typically thread-safe and manages an underlying connection pool (e.g., HikariCP).
 * Call [close] or use the interface within a `use` block to ensure proper resource cleanup.
 */
interface DataAccess : QueryOperations, AutoCloseable {

    /**
     * Executes a pre-configured [TransactionPlan] as a single, atomic transaction.
     *
     * Transaction Plans are specifically designed for scenarios where the sequence of operations
     * is determined dynamically (e.g., at the service or UI layer) and steps may depend on
     * results from previous operations (via [StepHandle][org.octavius.data.transaction.StepHandle]).
     *
     * @param plan The pre-built plan containing steps to be executed.
     * @param propagation Specifies how this transaction should behave if another transaction is already active.
     *                    Defaults to [TransactionPropagation.REQUIRED].
     * @return [DataResult] containing the results of all steps in the plan on success, or a [DatabaseException][org.octavius.data.exception.DatabaseException] on failure.
     */
    fun executeTransactionPlan(
        plan: TransactionPlan,
        propagation: TransactionPropagation = TransactionPropagation.REQUIRED,
        isolation: IsolationLevel = IsolationLevel.DEFAULT,
        readOnly: Boolean = false,
        timeoutSeconds: Int? = null,
    ): DataResult<TransactionPlanResult>

    /**
     * Executes a block of code within a managed transaction scope.
     *
     * The transaction follows a "fail-fast" commit policy:
     * - **Success:** If the `block` returns [DataResult.Success], the transaction is committed.
     * - **Failure:** If the `block` returns [DataResult.Failure], the transaction is automatically rolled back.
     * - **Exception:** If the `block` throws any exception, the transaction is rolled back, and the exception is translated to [DataResult.Failure].
     *
     * **Warning:**
     * Since the underlying implementation (on JVM) uses Spring JDBC and its `TransactionTemplate`,
     * the transaction state is bound to the current thread via `ThreadLocal`.
     * The `tx` object provided to the block is typically the same instance as the main [DataAccess]
     * object. While you should prefer using `tx` for clarity, calling methods directly on
     * the [DataAccess] instance inside the block will also participate in the same transaction,
     * provided they are executed on the same thread. Conversely, launching new threads or
     * coroutines with different dispatchers inside the block will NOT automatically
     * participate in the transaction.
     *
     * @param T The return type of the result encapsulated in [DataResult].
     * @param propagation Specifies how this transaction should behave if another transaction is already active.
     * @param block A lambda providing [QueryOperations] context for performing database operations within the transaction.
     * @return The result of the block as [DataResult].
     */
    fun <T> transaction(
        propagation: TransactionPropagation = TransactionPropagation.REQUIRED,
        isolation: IsolationLevel = IsolationLevel.DEFAULT,
        readOnly: Boolean = false,
        timeoutSeconds: Int? = null,
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
     * Always release the listener via [use][kotlin.io.use] or an explicit [PgChannelListener.close] call.
     *
     * @return A new [PgChannelListener] ready to subscribe to channels.
     */
    fun createChannelListener(): PgChannelListener

    /**
     * Closes the data access object and releases any underlying resources (e.g., connection pool).
     */
    override fun close()
}