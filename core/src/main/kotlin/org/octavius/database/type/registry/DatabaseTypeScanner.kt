package org.octavius.database.type.registry

import io.github.oshai.kotlinlogging.KotlinLogging
import org.octavius.data.exception.InitializationException
import org.octavius.data.exception.InitializationExceptionMessage
import org.octavius.data.exception.QueryContext
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Scans PostgreSQL database for type definitions.
 *
 * Queries the system catalogs to discover:
 * - ENUM types and their values
 * - COMPOSITE types and their attributes
 */
internal class DatabaseTypeScanner(
    private val jdbcTemplate: JdbcTemplate,
    private val dbSchemas: List<String>
) {
    /**
     * Scans configured schemas and returns discovered type definitions.
     */
    fun scan(): DatabaseScanResult {
        val enums = mutableMapOf<String, MutableList<String>>()
        val composites = mutableMapOf<String, MutableMap<String, String>>()

        try {
            val schemas = dbSchemas.toTypedArray()
            jdbcTemplate.query(SQL_QUERY_ALL_TYPES, { rs, _ ->
                val type = rs.getString("info_type")
                val name = rs.getString("type_name")
                val col1 = rs.getString("col1")
                val col2 = rs.getString("col2")

                when (type) {
                    "enum" -> enums.getOrPut(name) { mutableListOf() }.add(col1)
                    "composite" -> composites.getOrPut(name) { mutableMapOf() }[col1] = col2
                }
            }, schemas, schemas)
        } catch (e: Exception) {
            throw InitializationException(InitializationExceptionMessage.DB_QUERY_FAILED, cause = e,
                queryContext = QueryContext("", mapOf(), SQL_QUERY_ALL_TYPES, listOf(dbSchemas, dbSchemas)))
        }

        val procedures = scanProcedures()

        return DatabaseScanResult(enums, composites, procedures)
    }

    private fun scanProcedures(): Map<String, List<PgProcedureParam>> {
        // Key by (procName, oid) to correctly separate overloaded procedures
        val byOid = mutableMapOf<Pair<String, Long>, MutableList<PgProcedureParam>>()

        try {
            val schemas = dbSchemas.toTypedArray()
            jdbcTemplate.query(SQL_QUERY_PROCEDURES, { rs, _ ->
                val procName = rs.getString("proc_name")
                val procOid = rs.getLong("proc_oid")
                val paramName = rs.getString("param_name")
                val paramType = rs.getString("param_type")
                val paramMode = rs.getString("param_mode")
                val key = procName to procOid

                if (paramName != null) {
                    val mode = when (paramMode) {
                        "o" -> PgParamMode.OUT
                        "b" -> PgParamMode.INOUT
                        else -> PgParamMode.IN
                    }
                    byOid.getOrPut(key) { mutableListOf() }
                        .add(PgProcedureParam(paramName, paramType, mode))
                } else {
                    byOid.getOrPut(key) { mutableListOf() }
                }
            }, schemas, schemas)
        } catch (e: Exception) {
            throw InitializationException(InitializationExceptionMessage.DB_QUERY_FAILED, cause = e, queryContext = QueryContext("", mapOf(), SQL_QUERY_PROCEDURES, listOf(dbSchemas, dbSchemas)))
        }

        // Group by name and detect overloads
        val grouped = byOid.entries.groupBy({ it.key.first }, { it.value })
        val overloaded = grouped.filter { it.value.size > 1 }.keys
        if (overloaded.isNotEmpty()) {
            logger.warn { "Overloaded procedures detected: $overloaded — these are excluded from dataAccess.call() and can only be invoked via rawQuery()" }
        }

        return grouped.filterNot { it.key in overloaded }.mapValues { it.value.single() }
    }

    companion object {
        val logger = KotlinLogging.logger {}

        private const val SQL_QUERY_ENUM_TYPES = """
            SELECT
                'enum' AS info_type,
                t.typname AS type_name,
                e.enumlabel AS col1,
                NULL AS col2,
                e.enumsortorder::int AS sort_order
            FROM
                pg_type t
                JOIN pg_enum e ON t.oid = e.enumtypid
                JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
            WHERE
                n.nspname = ANY(?)
        """

        private const val SQL_QUERY_COMPOSITE_TYPES = """
            SELECT
                'composite' AS info_type,
                t.typname AS type_name,
                a.attname AS col1,
                at.typname AS col2,
                a.attnum AS sort_order
            FROM
                pg_type t
                JOIN pg_class c ON t.typrelid = c.oid
                JOIN pg_attribute a ON a.attrelid = c.oid
                JOIN pg_type at ON a.atttypid = at.oid
                JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
            WHERE
                t.typtype = 'c'
                AND a.attnum > 0
                AND NOT a.attisdropped
                AND n.nspname = ANY(?)
        """

        private const val SQL_QUERY_ALL_TYPES = """
            $SQL_QUERY_ENUM_TYPES
            UNION ALL
            $SQL_QUERY_COMPOSITE_TYPES
            ORDER BY
                type_name, sort_order
        """

        /**
         * Scans stored procedures from pg_proc.
         *
         * PostgreSQL stores parameter metadata differently depending on the parameter modes:
         * - **All-IN procedures**: `proargmodes` and `proallargtypes` are NULL.
         *   Parameter names come from `proargnames`, types from `proargtypes` (oidvector).
         * - **Procedures with OUT/INOUT**: `proargmodes` and `proallargtypes` are populated.
         * - **Parameterless procedures**: `proargnames` is NULL, `proargtypes` is empty.
         *   These emit a single row with NULLs so the procedure name is still registered.
         */
        private const val SQL_QUERY_PROCEDURES = """
            SELECT proc_oid, proc_name, param_name, param_type, param_mode FROM (
                -- Procedures WITH parameters
                SELECT
                    p.oid AS proc_oid,
                    p.proname AS proc_name,
                    args.param_name,
                    t.typname AS param_type,
                    args.param_mode,
                    args.ordinal AS param_ordinal
                FROM
                    pg_proc p
                    JOIN pg_namespace n ON p.pronamespace = n.oid
                    CROSS JOIN LATERAL ROWS FROM (
                        unnest(p.proargnames),
                        unnest(COALESCE(p.proallargtypes, p.proargtypes::oid[])),
                        unnest(COALESCE(p.proargmodes, array_fill('i'::"char", ARRAY[cardinality(COALESCE(p.proallargtypes, p.proargtypes::oid[]))])))
                    ) WITH ORDINALITY AS args(param_name, param_oid, param_mode, ordinal)
                    JOIN pg_type t ON t.oid = args.param_oid
                WHERE
                    p.prokind = 'p'
                    AND p.proargnames IS NOT NULL
                    AND n.nspname = ANY(?)

                UNION ALL

                -- Parameterless procedures
                SELECT
                    p.oid AS proc_oid,
                    p.proname AS proc_name,
                    NULL AS param_name,
                    NULL AS param_type,
                    NULL AS param_mode,
                    0 AS param_ordinal
                FROM
                    pg_proc p
                    JOIN pg_namespace n ON p.pronamespace = n.oid
                WHERE
                    p.prokind = 'p'
                    AND p.proargnames IS NULL
                    AND n.nspname = ANY(?)
            ) sub
            ORDER BY proc_name, param_ordinal
        """
    }
}