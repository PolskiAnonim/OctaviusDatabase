package org.octavius.database.jdbc

import org.octavius.data.transaction.IsolationLevel
import org.octavius.data.transaction.TransactionPropagation
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import javax.sql.DataSource

class SpringJdbcTransactionProvider(
    override val dataSource: DataSource
) : JdbcTransactionProvider {

    private val transactionManager = DataSourceTransactionManager(dataSource)

    override fun getConnection(): Connection {
        return DataSourceUtils.doGetConnection(dataSource)
    }

    override fun releaseConnection(connection: Connection) {
        DataSourceUtils.doReleaseConnection(connection, dataSource)
    }

    override fun <T> execute(
        propagation: TransactionPropagation,
        isolation: IsolationLevel,
        readOnly: Boolean,
        timeoutSeconds: Int?,
        block: (TransactionStatus) -> T
    ): T {
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
}
