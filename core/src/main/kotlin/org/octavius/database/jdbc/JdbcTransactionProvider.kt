package org.octavius.database.jdbc

import org.octavius.data.transaction.TransactionPropagation
import java.sql.Connection
import javax.sql.DataSource

/**
 * Interface for managing database connections and transactions.
 * Decouples the core logic from specific transaction management implementations (like Spring).
 */
internal interface JdbcTransactionProvider {
    val dataSource: DataSource
    
    fun getConnection(): Connection
    fun releaseConnection(connection: Connection)
    
    fun <T> execute(propagation: TransactionPropagation, block: (TransactionStatus) -> T): T
}

/**
 * Minimal interface to control the current transaction.
 */
internal interface TransactionStatus {
    fun setRollbackOnly()
}
