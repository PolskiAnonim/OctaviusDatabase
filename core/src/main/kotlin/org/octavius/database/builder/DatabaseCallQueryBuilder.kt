package org.octavius.database.builder

import io.github.oshai.kotlinlogging.KotlinLogging
import org.octavius.data.DataResult
import org.octavius.data.builder.CallQueryBuilder
import org.octavius.data.exception.QueryExecutionException
import org.octavius.database.type.KotlinToPostgresConverter
import org.octavius.database.type.PostgresToKotlinConverter
import org.octavius.database.type.registry.PgParamMode
import org.octavius.database.type.registry.PgProcedureDefinition
import org.octavius.database.type.registry.TypeRegistry
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Types

internal class DatabaseCallQueryBuilder(
    private val jdbcTemplate: JdbcTemplate,
    private val typeRegistry: TypeRegistry,
    private val kotlinToPostgresConverter: KotlinToPostgresConverter,
    private val postgresToKotlinConverter: PostgresToKotlinConverter,
    private val procedureName: String
) : CallQueryBuilder {

    override fun execute(params: Map<String, Any?>): DataResult<Map<String, Any?>> {
        val procDef = typeRegistry.getProcedureDefinition(procedureName)
        val plan = buildCallPlan(procDef, params)

        logger.debug { "Executing procedure call: ${plan.sql} with params: $params" }

        return try {
            val outValues = jdbcTemplate.execute(ConnectionCallback { connection ->
                connection.prepareCall(plan.sql).use { cs ->
                    for ((position, value) in plan.inParams) {
                        cs.setObject(position, value)
                    }

                    for ((position, _, pgTypeName) in plan.outParams) {
                        cs.registerOutParameter(position, pgTypeToJdbcType(pgTypeName))
                    }

                    cs.execute()

                    plan.outParams.associate { (position, name, pgTypeName) ->
                        val rawValue = cs.getString(position)
                        name to postgresToKotlinConverter.convert(rawValue, pgTypeName)
                    }
                }
            })
            DataResult.Success(outValues)
        } catch (e: Exception) {
            val executionException = QueryExecutionException(
                sql = plan.sql,
                params = params,
                cause = e
            )
            logger.error(executionException) { "Procedure call failed" }
            DataResult.Failure(executionException)
        }
    }

    // -------------------------------------------------------------------------
    //  CALL PLAN BUILDING
    // -------------------------------------------------------------------------

    private data class CallPlan(
        val sql: String,
        val inParams: List<Pair<Int, Any?>>,
        val outParams: List<Triple<Int, String, String>>
    )

    /**
     * Builds the CALL SQL and tracks JDBC positions for IN and OUT parameters.
     *
     * Each IN parameter may expand to multiple JDBC `?` placeholders
     * (e.g., a composite with 5 fields becomes `ROW(?, ?, ?, ?, ?)::type_name`,
     * a list becomes `ARRAY[?, ?, ?]`). OUT positions are calculated after
     * all preceding expansions.
     */
    private fun buildCallPlan(procDef: PgProcedureDefinition, userParams: Map<String, Any?>): CallPlan {
        val sqlFragments = mutableListOf<String>()
        val inParams = mutableListOf<Pair<Int, Any?>>()
        val outParams = mutableListOf<Triple<Int, String, String>>()
        var nextPosition = 1

        for (param in procDef.params) {
            when (param.mode) {
                PgParamMode.IN -> {
                    val (fragment, expandedValues) = kotlinToPostgresConverter.expandSingleValue(userParams[param.name])
                    sqlFragments.add(fragment)
                    expandedValues.forEachIndexed { i, value ->
                        inParams.add((nextPosition + i) to value)
                    }
                    nextPosition += expandedValues.size
                }

                PgParamMode.OUT -> {
                    sqlFragments.add("?")
                    outParams.add(Triple(nextPosition, param.name, param.typeName))
                    nextPosition++
                }

                PgParamMode.INOUT -> {
                    val (fragment, expandedValues) = kotlinToPostgresConverter.expandSingleValue(userParams[param.name])
                    check(expandedValues.size == 1) {
                        "INOUT parameter '${param.name}' expanded to ${expandedValues.size} JDBC parameters. " +
                                "INOUT parameters must be simple types (single JDBC parameter)."
                    }
                    sqlFragments.add(fragment)
                    inParams.add(nextPosition to expandedValues[0])
                    outParams.add(Triple(nextPosition, param.name, param.typeName))
                    nextPosition++
                }
            }
        }

        val sql = "CALL $procedureName(${sqlFragments.joinToString(", ")})"
        return CallPlan(sql, inParams, outParams)
    }

    // -------------------------------------------------------------------------
    //  JDBC TYPE MAPPING
    // -------------------------------------------------------------------------

    private fun pgTypeToJdbcType(pgTypeName: String): Int = when (pgTypeName) {
        "int2", "smallserial" -> Types.SMALLINT
        "int4", "serial" -> Types.INTEGER
        "int8", "bigserial" -> Types.BIGINT
        "float4" -> Types.REAL
        "float8" -> Types.DOUBLE
        "numeric" -> Types.NUMERIC
        "text", "varchar", "char" -> Types.VARCHAR
        "bool" -> Types.BOOLEAN
        "date" -> Types.DATE
        "timestamp" -> Types.TIMESTAMP
        "timestamptz" -> Types.TIMESTAMP_WITH_TIMEZONE
        "time" -> Types.TIME
        "timetz" -> Types.TIME_WITH_TIMEZONE
        "bytea" -> Types.BINARY
        "uuid" -> Types.OTHER
        "json", "jsonb" -> Types.VARCHAR
        else -> Types.VARCHAR
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
