package org.octavius.database

import io.github.oshai.kotlinlogging.KotlinLogging
import org.octavius.data.DataAccess
import org.octavius.data.DataResult
import org.octavius.data.QueryOperations
import org.octavius.data.builder.*
import org.octavius.data.exception.BuilderException
import org.octavius.data.exception.DatabaseException
import org.octavius.data.exception.QueryContext
import org.octavius.data.notification.PgChannelListener
import org.octavius.data.transaction.TransactionPlan
import org.octavius.data.transaction.TransactionPlanResult
import org.octavius.data.transaction.TransactionPropagation
import org.octavius.database.builder.*
import org.octavius.database.exception.ExceptionTranslator
import org.octavius.database.jdbc.JdbcTemplate
import org.octavius.database.jdbc.JdbcTransactionProvider
import org.octavius.database.jdbc.RowMappers
import org.octavius.database.notification.DatabasePgChannelListener
import org.octavius.database.transaction.TransactionPlanExecutor
import org.octavius.database.type.KotlinToPostgresConverter
import org.octavius.database.type.registry.TypeRegistry
import java.sql.Connection

internal class DatabaseAccess(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionManager: JdbcTransactionProvider,
    typeRegistry: TypeRegistry,
    private val kotlinToPostgresConverter: KotlinToPostgresConverter,
    private val listenerConnectionFactory: () -> Connection,
    private val onClose: (() -> Unit)? = null
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

    override fun insertInto(table: String): InsertQueryBuilder {
        return DatabaseInsertQueryBuilder(jdbcTemplate, kotlinToPostgresConverter, rowMappers, table)
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
        return transactionManager.execute(propagation) { status ->
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
            } catch (e: BuilderException) {
                status.setRollbackOnly()
                throw e
            } catch (e: DatabaseException) {
                status.setRollbackOnly()
                // There is no additional context here so there is nothing to do with this exception
                // It should be logged - technically someone is throwing it instead of returning or it is from toDataMap/toDataObject
                // Because it is unreasonable to throw from queries we are assuming that this error has not been logged
                logger.error(e) { "A DatabaseException was thrown inside the transaction block. Rolling back." }
                DataResult.Failure(e)
            } catch (e: Exception) {
                // Catch any other unexpected exception
                status.setRollbackOnly()
                // There is no additional context here
                val ex = ExceptionTranslator.translate(e, QueryContext("N/A", mapOf()))
                logger.error(e) { "An unexpected exception was thrown inside the transaction block. Rolling back." }
                // Wrap it in standard Failure
                DataResult.Failure(ex)
            }
        }
    }

    override fun notify(channel: String, payload: String?): DataResult<Unit> {
        return rawQuery("SELECT pg_notify(@channel, @payload)").toField<Unit>("channel" to channel, "payload" to payload)
    }

    override fun createChannelListener(): PgChannelListener {
        val connection = listenerConnectionFactory()
        return DatabasePgChannelListener(connection)
    }

    override fun close() {
        onClose?.invoke()
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
