package org.octavius.database.jdbc

import org.octavius.data.transaction.IsolationLevel
import org.octavius.data.transaction.TransactionPropagation
import java.sql.Connection
import java.sql.Statement
import javax.sql.DataSource

/**
 * Default implementation of [JdbcTransactionProvider] using [ThreadLocal] to manage transactions.
 * This implementation does not depend on any external transaction managers (like Spring).
 * 
 * It supports:
 * - Nested transactions via Savepoints.
 * - Propagation (REQUIRED, REQUIRES_NEW).
 * - Automatic restoration of connection state (isolation level, read-only) after transaction completion.
 * - Transaction timeouts via PostgreSQL 'statement_timeout'.
 */
internal class DefaultJdbcTransactionProvider(override val dataSource: DataSource) : JdbcTransactionProvider {

    /**
     * Stack of active transactions for the current thread. 
     * Multiple contexts exist when propagation is REQUIRES_NEW.
     */
    private val transactionStack = ThreadLocal<MutableList<TransactionContext>>()

    private fun getStackOrNull(): MutableList<TransactionContext>? = transactionStack.get()

    private fun getOrCreateStack(): MutableList<TransactionContext> {
        var stack = transactionStack.get()
        if (stack == null) {
            stack = mutableListOf()
            transactionStack.set(stack)
        }
        return stack
    }

    override fun getConnection(): Connection {
        val stack = getStackOrNull()
        return if (!stack.isNullOrEmpty()) {
            // Use the connection bound to the most recent transaction context
            stack.last().connection
        } else {
            // No active transaction, get a raw connection from the pool
            dataSource.connection
        }
    }

    override fun releaseConnection(connection: Connection) {
        val stack = getStackOrNull()
        // Only close if it's not a connection managed by the current transaction stack
        if (stack.isNullOrEmpty() || stack.last().connection != connection) {
            connection.close()
        }
    }

    override fun <T> execute(
        propagation: TransactionPropagation,
        isolation: IsolationLevel,
        readOnly: Boolean,
        timeoutSeconds: Int?,
        block: (TransactionStatus) -> T
    ): T {
        val stack = getOrCreateStack()
        val currentContext = stack.lastOrNull()

        return when (propagation) {
            TransactionPropagation.REQUIRED -> {
                if (currentContext != null) {
                    executeExisting(currentContext, block)
                } else {
                    executeNew(stack, isolation, readOnly, timeoutSeconds, block)
                }
            }

            TransactionPropagation.REQUIRES_NEW -> {
                executeNew(stack, isolation, readOnly, timeoutSeconds, block)
            }

            TransactionPropagation.NESTED -> {
                if (currentContext != null) {
                    executeNested(currentContext, block)
                } else {
                    executeNew(stack, isolation, readOnly, timeoutSeconds, block)
                }
            }
        }
    }

    /**
     * Joins an existing transaction. Rollback in this block will mark the parent transaction as rollback-only.
     */
    private fun <T> executeExisting(context: TransactionContext, block: (TransactionStatus) -> T): T {
        val status = DefaultTransactionStatus { context.rollbackOnly = true }
        return try {
            block(status)
        } catch (e: Throwable) {
            context.rollbackOnly = true
            throw e
        }
    }

    /**
     * Starts a completely new transaction on a new connection.
     * Saves original connection state and restores it in 'finally' block.
     */
    private fun <T> executeNew(
        stack: MutableList<TransactionContext>,
        isolation: IsolationLevel,
        readOnly: Boolean,
        timeoutSeconds: Int?,
        block: (TransactionStatus) -> T
    ): T {
        val connection = dataSource.connection
        var originalState: OriginalConnectionState?

        val context = try {
            connection.autoCommit = false
            // 1. Configure connection and capture original state for restoration
            originalState = connection.applyConfigAndSaveState(isolation, readOnly, timeoutSeconds)
            TransactionContext(connection)
        } catch (e: Throwable) {
            runCatching { connection.close() }
            if (stack.isEmpty()) transactionStack.remove()
            throw e
        }

        stack.add(context)

        val status = DefaultTransactionStatus { context.rollbackOnly = true }

        return try {
            val result = block(status)

            // 2. Commit or Rollback based on status
            if (context.rollbackOnly) {
                runCatching { connection.rollback() }
            } else {
                connection.commit()
            }

            result
        } catch (e: Throwable) {
            runCatching { connection.rollback() }
            throw e
        } finally {
            // 3. Cleanup: restore state, reset autocommit, close connection, and pop stack
            originalState.let { connection.restoreState(it) }

            runCatching { connection.autoCommit = true }
            runCatching { connection.close() }

            stack.removeLast()

            if (stack.isEmpty()) {
                transactionStack.remove()
            }
        }
    }

    /**
     * Creates a Savepoint within the current connection.
     */
    private fun <T> executeNested(context: TransactionContext, block: (TransactionStatus) -> T): T {
        val savepoint = context.connection.setSavepoint()
        var nestedRollbackOnly = false
        val status = DefaultTransactionStatus { nestedRollbackOnly = true }

        return try {
            val result = block(status)
            if (nestedRollbackOnly) {
                context.connection.rollback(savepoint)
            } else {
                runCatching { context.connection.releaseSavepoint(savepoint) }
            }
            result
        } catch (e: Throwable) {
            runCatching { context.connection.rollback(savepoint) }
            throw e
        }
    }

    override fun applyTimeout(statement: Statement) {
        // Not needed for default provider as it uses 'SET LOCAL statement_timeout' 
        // which applies to the whole session (transaction duration).
    }

    private class TransactionContext(
        val connection: Connection
    ) {
        var rollbackOnly: Boolean = false
    }

    private class DefaultTransactionStatus(private val onRollbackOnly: () -> Unit) : TransactionStatus {
        override fun setRollbackOnly() {
            onRollbackOnly()
        }
    }
}

/**
 * Capture of connection properties to be restored after transaction.
 */
private class OriginalConnectionState(val isolation: Int, val readOnly: Boolean)

private fun Connection.applyConfigAndSaveState(
    isolation: IsolationLevel, readOnly: Boolean, timeoutSeconds: Int?
): OriginalConnectionState {
    val state = OriginalConnectionState(this.transactionIsolation, this.isReadOnly)

    if (isolation != IsolationLevel.DEFAULT) {
        this.transactionIsolation = isolation.jdbcValue
    }

    this.isReadOnly = readOnly

    // Implementation of transaction timeout via PostgreSQL LOCAL setting
    timeoutSeconds?.let {
        this.createStatement().use { stmt -> stmt.execute("SET LOCAL statement_timeout = '${it}s'") }
    }
    return state
}

private fun Connection.restoreState(state: OriginalConnectionState) {
    runCatching { this.transactionIsolation = state.isolation }
    runCatching { this.isReadOnly = state.readOnly }
}