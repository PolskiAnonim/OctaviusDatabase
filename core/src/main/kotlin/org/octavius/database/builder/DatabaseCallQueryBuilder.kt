package org.octavius.database.builder

import io.github.oshai.kotlinlogging.KotlinLogging
import org.octavius.data.DataResult
import org.octavius.data.assertNotNull
import org.octavius.data.builder.CallQueryBuilder
import org.octavius.data.map
import org.octavius.database.RowMappers
import org.octavius.database.type.KotlinToPostgresConverter
import org.octavius.database.type.registry.PgParamMode
import org.octavius.database.type.registry.PgProcedureDefinition
import org.octavius.database.type.registry.TypeRegistry
import org.springframework.jdbc.core.JdbcTemplate

internal class DatabaseCallQueryBuilder(
    private val jdbcTemplate: JdbcTemplate,
    private val typeRegistry: TypeRegistry,
    private val kotlinToPostgresConverter: KotlinToPostgresConverter,
    private val rowMappers: RowMappers,
    private val procedureName: String
) : CallQueryBuilder {

    override fun execute(params: Map<String, Any?>): DataResult<Map<String, Any?>> {
        val procDef = typeRegistry.getProcedureDefinition(procedureName)
        val plan = buildCallPlan(procDef, params)

        logger.debug { "Executing procedure call: ${plan.sql} with params: $params" }

        val rawQuery = DatabaseRawQueryBuilder(jdbcTemplate, kotlinToPostgresConverter, rowMappers, plan.sql)

        return if (!plan.hasOutParams) {
            rawQuery.execute(plan.params).map { emptyMap() }
        } else {
            rawQuery.toSingle(plan.params).assertNotNull()
        }
    }

    // -------------------------------------------------------------------------
    //  CALL PLAN BUILDING
    // -------------------------------------------------------------------------

    /**
     * @property sql           The CALL statement, e.g. `CALL proc(:a, NULL::text)`
     * @property params        Filtered user params (IN/INOUT only) for rawQuery binding
     * @property hasOutParams  Whether the procedure has OUT/INOUT params (determines execution path)
     */
    private data class CallPlan(
        val sql: String,
        val params: Map<String, Any?>,
        val hasOutParams: Boolean
    )

    /**
     * Builds the CALL SQL from procedure metadata.
     *
     * - **IN** params become `:paramName` named placeholders — rawQuery's
     *   `expandParametersInQuery` handles composite/array expansion.
     * - **OUT** params become `NULL::typeName` literals — PostgreSQL returns
     *   their values as ResultSet columns.
     * - **INOUT** params are both bound (`:paramName`) and recorded as OUT names.
     */
    private fun buildCallPlan(procDef: PgProcedureDefinition, userParams: Map<String, Any?>): CallPlan {
        val sqlFragments = mutableListOf<String>()
        val filteredParams = mutableMapOf<String, Any?>()
        var hasOutParams = false

        for (param in procDef.params) {
            when (param.mode) {
                PgParamMode.IN -> {
                    sqlFragments.add(":${param.name}")
                    filteredParams[param.name] = userParams[param.name]
                }

                PgParamMode.OUT -> {
                    sqlFragments.add("NULL::${param.typeName}")
                    hasOutParams = true
                }

                PgParamMode.INOUT -> {
                    sqlFragments.add(":${param.name}")
                    filteredParams[param.name] = userParams[param.name]
                    hasOutParams = true
                }
            }
        }

        val sql = "CALL $procedureName(${sqlFragments.joinToString(", ")})"
        return CallPlan(sql, filteredParams, hasOutParams)
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
