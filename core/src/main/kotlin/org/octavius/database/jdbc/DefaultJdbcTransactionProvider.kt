package org.octavius.database.jdbc

import org.octavius.data.transaction.IsolationLevel
import org.octavius.data.transaction.TransactionPropagation
import java.sql.Connection
import javax.sql.DataSource

/**
 * Default implementation of [JdbcTransactionProvider] using [ThreadLocal] to manage transactions.
 * This implementation does not depend on any external transaction managers (like Spring).
 */
internal class DefaultJdbcTransactionProvider(override val dataSource: DataSource) : JdbcTransactionProvider {

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
            stack.last().connection
        } else {
            dataSource.connection
        }
    }

    override fun releaseConnection(connection: Connection) {
        val stack = getStackOrNull()
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

    private fun <T> executeExisting(context: TransactionContext, block: (TransactionStatus) -> T): T {
        val status = DefaultTransactionStatus { context.rollbackOnly = true }
        return try {
            block(status)
        } catch (e: Throwable) {
            context.rollbackOnly = true
            throw e
        }
    }

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
            originalState.let { connection.restoreState(it) }

            runCatching { connection.autoCommit = true }
            runCatching { connection.close() }

            stack.removeLast()

            if (stack.isEmpty()) {
                transactionStack.remove()
            }
        }
    }

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

private class OriginalConnectionState(val isolation: Int, val readOnly: Boolean)

private fun Connection.applyConfigAndSaveState(
    isolation: IsolationLevel, readOnly: Boolean, timeoutSeconds: Int?
): OriginalConnectionState {
    val state = OriginalConnectionState(this.transactionIsolation, this.isReadOnly)

    if (isolation != IsolationLevel.DEFAULT) {
        this.transactionIsolation = isolation.jdbcValue
    }

    this.isReadOnly = readOnly

    timeoutSeconds?.let {
        this.createStatement().use { stmt -> stmt.execute("SET LOCAL statement_timeout = '${it}s'") }
    }
    return state
}

private fun Connection.restoreState(state: OriginalConnectionState) {
    runCatching { this.transactionIsolation = state.isolation }
    runCatching { this.isReadOnly = state.readOnly }
}