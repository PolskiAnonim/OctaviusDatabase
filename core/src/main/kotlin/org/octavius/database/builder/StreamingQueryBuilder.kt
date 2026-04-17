package org.octavius.database.builder

import io.github.oshai.kotlinlogging.KotlinLogging
import org.octavius.data.DataResult
import org.octavius.data.builder.StreamingTerminalMethods
import org.octavius.data.exception.QueryContext
import org.octavius.database.exception.ExceptionTranslator
import org.octavius.database.jdbc.RowMapper
import org.octavius.database.type.PositionalQuery
import kotlin.reflect.KClass

internal class StreamingQueryBuilder(
    private val builder: AbstractQueryBuilder<*>,
    private val fetchSize: Int
) : StreamingTerminalMethods {

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private fun <T> executeStream(
        params: Map<String, Any?>,
        rowMapper: RowMapper<T>,
        action: (item: T) -> Unit
    ): DataResult<Unit> {
        // Declare variables outside to make them accessible in `catch`
        val originalSql = builder.buildSql()
        var positionalQuery: PositionalQuery? = null

        return try {
            positionalQuery = builder.kotlinToPostgresConverter.toPositionalQuery(originalSql, params)

            logger.debug {
                """
                Executing query (original): $originalSql with params: $params
                  -> (database): ${positionalQuery.sql} with positional params: ${positionalQuery.params}
                """.trimIndent()
            }

            builder.jdbcTemplate.query(positionalQuery, fetchSize) { rs ->
                var rowNum = 0
                while (rs.next()) {
                    val mappedItem = rowMapper.mapRow(rs, rowNum++)
                    action(mappedItem)
                }
            }

            DataResult.Success(Unit)
        } catch (e: Exception) {
            val queryContext = QueryContext(
                sql = originalSql,
                parameters = params,
                dbSql = positionalQuery?.sql,
                dbParameters = positionalQuery?.params
            )
            val translatedException = ExceptionTranslator.translate(e, queryContext)
            
            logger.error(translatedException) { "Database error executing streaming query" }
            DataResult.Failure(translatedException)
        }
    }

    // --- Public terminal methods that use the helper method ---

    override fun forEachRow(params: Map<String, Any?>, action: (row: Map<String, Any?>) -> Unit): DataResult<Unit> {
        return executeStream(params, builder.rowMappers.ColumnNameMapper(), action)
    }

    override fun <T : Any> forEachRowOf(
        kClass: KClass<T>,
        params: Map<String, Any?>,
        action: (obj: T) -> Unit
    ): DataResult<Unit> {
        return executeStream(params, builder.rowMappers.DataObjectMapper(kClass), action)
    }
}
