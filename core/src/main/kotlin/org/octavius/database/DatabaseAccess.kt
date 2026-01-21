package org.octavius.database

import io.github.oshai.kotlinlogging.KotlinLogging
import org.octavius.data.DataAccess
import org.octavius.data.DataResult
import org.octavius.data.QueryOperations
import org.octavius.data.builder.*
import org.octavius.data.exception.DatabaseException
import org.octavius.data.exception.TransactionException
import org.octavius.data.transaction.TransactionPlan
import org.octavius.data.transaction.TransactionPlanResult
import org.octavius.data.transaction.TransactionPropagation
import org.octavius.database.builder.*
import org.octavius.database.transaction.TransactionPlanExecutor
import org.octavius.database.type.KotlinToPostgresConverter
import org.octavius.database.type.registry.TypeRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

internal class DatabaseAccess(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionManager: DataSourceTransactionManager,
    typeRegistry: TypeRegistry,
    private val kotlinToPostgresConverter: KotlinToPostgresConverter
) : DataAccess {
    private val rowMappers = RowMappers(typeRegistry)
    val transactionPlanExecutor = TransactionPlanExecutor(transactionManager)
    // --- QueryOperations implementation (for single queries and transaction usage) ---

    override fun select(vararg columns: String): SelectQueryBuilder {
        return DatabaseSelectQueryBuilder(
            jdbcTemplate,
            rowMappers,
            kotlinToPostgresConverter,
            columns.joinToString(",\n")
        )
    }

    override fun update(table: String): UpdateQueryBuilder {
        return DatabaseUpdateQueryBuilder(jdbcTemplate, kotlinToPostgresConverter, rowMappers, table)
    }

    override fun insertInto(table: String, columns: List<String>): InsertQueryBuilder {
        return DatabaseInsertQueryBuilder(jdbcTemplate, kotlinToPostgresConverter, rowMappers, table, columns)
    }

    override fun deleteFrom(table: String): DeleteQueryBuilder {
        return DatabaseDeleteQueryBuilder(jdbcTemplate, kotlinToPostgresConverter, rowMappers, table)
    }

    override fun rawQuery(sql: String): RawQueryBuilder {
        return DatabaseRawQueryBuilder(jdbcTemplate, kotlinToPostgresConverter, rowMappers, sql)
    }

    //--- Transaction management implementation ---

    override fun executeTransactionPlan(
        plan: TransactionPlan,
        propagation: TransactionPropagation
    ): DataResult<TransactionPlanResult> {
        return transactionPlanExecutor.execute(plan, propagation)
    }

    override fun <T> transaction(
        propagation: TransactionPropagation,
        block: (tx: QueryOperations) -> DataResult<T>
    ): DataResult<T> {
        // Create and configure transaction template
        val transactionTemplate = TransactionTemplate(transactionManager).apply {
            propagationBehavior = when (propagation) {
                TransactionPropagation.REQUIRED -> TransactionDefinition.PROPAGATION_REQUIRED
                TransactionPropagation.REQUIRES_NEW -> TransactionDefinition.PROPAGATION_REQUIRES_NEW
                TransactionPropagation.NESTED -> TransactionDefinition.PROPAGATION_NESTED
            }
        }
        return transactionTemplate.execute { status ->
            try {
                // `this` is an instance of `QueryOperations`, so we pass it directly.
                val result = block(this)

                // If any operation inside the block returned Failure, we roll back the transaction.
                // This allows controlled rollback without throwing an exception!
                if (result is DataResult.Failure) {
                    logger.warn { "Transaction block returned Failure. Rolling back transaction." }
                    status.setRollbackOnly()
                }
                result // Return original result (Success or Failure)
            } catch (e: DatabaseException) {
                // Catch our own exceptions to avoid wrapping them again
                status.setRollbackOnly()
                logger.error(e) { "A DatabaseException was thrown inside the transaction block. Rolling back." }
                DataResult.Failure(e)
            } catch (e: Exception) {
                // Catch any other unexpected exception
                status.setRollbackOnly()
                logger.error(e) { "An unexpected exception was thrown inside the transaction block. Rolling back." }
                // Wrap it in our standard Failure
                DataResult.Failure(
                    TransactionException(cause = e)
                )
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
