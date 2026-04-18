package org.octavius.database.jdbc

import org.octavius.data.transaction.IsolationLevel
import org.octavius.data.transaction.TransactionPropagation
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.sql.Statement
import javax.sql.DataSource

/**
 * Implementation of [JdbcTransactionProvider] that delegates all transaction management to Spring Framework.
 * 
 * This allows Octavius to participate in Spring-managed transactions (e.g., using `@Transactional`)
 * and use Spring's [DataSourceTransactionManager].
 */
class SpringJdbcTransactionProvider(
    override val dataSource: DataSource
) : JdbcTransactionProvider {

    private val transactionManager = DataSourceTransactionManager(dataSource)

    override fun getConnection(): Connection {
        // Delegates to Spring's DataSourceUtils to obtain a connection bound to the current transaction
        return DataSourceUtils.doGetConnection(dataSource)
    }

    override fun releaseConnection(connection: Connection) {
        // Delegates to Spring's DataSourceUtils to correctly release (or NOT close) the connection
        DataSourceUtils.doReleaseConnection(connection, dataSource)
    }

    override fun <T> execute(
        propagation: TransactionPropagation,
        isolation: IsolationLevel,
        readOnly: Boolean,
        timeoutSeconds: Int?,
        block: (TransactionStatus) -> T
    ): T {
        // Maps Octavius propagation to Spring's TransactionDefinition
        val transactionTemplate = TransactionTemplate(transactionManager).apply {
            propagationBehavior = when (propagation) {
                TransactionPropagation.REQUIRED -> TransactionDefinition.PROPAGATION_REQUIRED
                TransactionPropagation.REQUIRES_NEW -> TransactionDefinition.PROPAGATION_REQUIRES_NEW
                TransactionPropagation.NESTED -> TransactionDefinition.PROPAGATION_NESTED
            }
            isolationLevel = isolation.jdbcValue
            isReadOnly = readOnly
            timeout = timeoutSeconds ?: -1
        }
        
        return transactionTemplate.execute { status ->
            val wrappedStatus = object : TransactionStatus {
                override fun setRollbackOnly() {
                    status.setRollbackOnly()
                }
            }
            block(wrappedStatus)
        }
    }

    override fun applyTimeout(statement: Statement) {
        // CRITICAL: Spring's transaction timeout is stored in a ThreadLocal. 
        // It must be manually applied to every JDBC Statement to be enforced.
        DataSourceUtils.applyTransactionTimeout(statement, dataSource)
    }
}
