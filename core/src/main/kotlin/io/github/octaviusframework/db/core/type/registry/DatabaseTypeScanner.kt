package io.github.octaviusframework.db.core.type.registry

import io.github.octaviusframework.db.api.exception.InitializationException
import io.github.octaviusframework.db.api.exception.InitializationExceptionMessage
import io.github.octaviusframework.db.api.exception.QueryContext
import io.github.octaviusframework.db.api.type.QualifiedName
import io.github.octaviusframework.db.core.jdbc.JdbcTemplate
import io.github.octaviusframework.db.core.type.PositionalQuery
import io.github.oshai.kotlinlogging.KotlinLogging

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
        val enums = mutableMapOf<Int, MutableList<String>>()
        val composites = mutableMapOf<Int, MutableMap<String, Int>>()
        val allOidNames = mutableMapOf<Int, QualifiedName>()
        val arrayOids = mutableMapOf<Int, Int>()

        val schemas = (dbSchemas + "pg_catalog").distinct().toTypedArray()

        try {
            val query1 = PositionalQuery(SQL_QUERY_DETAILS, listOf(schemas, schemas))
            jdbcTemplate.query(query1) { rs, _ ->
                val type = rs.getString("info_type")
                val oid = rs.getInt("type_oid")
                val col1 = rs.getString("col1")

                if (type == "enum") {
                    enums.getOrPut(oid) { mutableListOf() }.add(col1)
                } else if (type == "composite") {
                    val col2 = rs.getInt("col2")
                    composites.getOrPut(oid) { mutableMapOf() }[col1] = col2
                }
            }
        } catch (e: Exception) {
            throw InitializationException(
                InitializationExceptionMessage.DB_QUERY_FAILED,
                cause = e,
                queryContext = QueryContext("", mapOf(), SQL_QUERY_DETAILS, listOf(schemas, schemas))
            )
        }

        try {
            val query2 = PositionalQuery(SQL_QUERY_OIDS_AND_ARRAYS, listOf(schemas))
            jdbcTemplate.query(query2) { rs, _ ->
                val oid = rs.getInt("type_oid")
                val schema = rs.getString("schema_name")
                val name = rs.getString("type_name")
                val arrayOid = rs.getInt("array_oid")

                allOidNames[oid] = QualifiedName(schema, name, isArray = false)
                allOidNames[arrayOid] = QualifiedName(schema, name, isArray = true)
                arrayOids[arrayOid] =  oid
            }
        } catch (e: Exception) {
            throw InitializationException(
                InitializationExceptionMessage.DB_QUERY_FAILED,
                cause = e,
                queryContext = QueryContext("", mapOf(), SQL_QUERY_OIDS_AND_ARRAYS, listOf(schemas))
            )
        }


        return DatabaseScanResult(enums, composites, allOidNames, arrayOids)
    }

    fun fetchSearchPath(): List<String> {
        return try {
            jdbcTemplate.query(PositionalQuery("SELECT UNNEST(current_schemas(true))", emptyList())) { rs, _ ->
                rs.getString(1)
            }
        } catch (e: Exception) {
            logger.warn { "Failed to fetch search_path, falling back to default schemas. Error: ${e.message}" }
            dbSchemas
        }
    }

    companion object {
        val logger = KotlinLogging.logger {}

        private const val SQL_QUERY_DETAILS = """
            SELECT
                'enum' AS info_type,
                t.oid AS type_oid,
                e.enumlabel AS col1,
                NULL::int AS col2,
                e.enumsortorder::int AS sort_order
            FROM pg_type t
            JOIN pg_enum e ON t.oid = e.enumtypid
            JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
            WHERE n.nspname = ANY(?)
            UNION ALL
            SELECT
                'composite' AS info_type,
                t.oid AS type_oid,
                a.attname AS col1,
                a.atttypid::int AS col2,
                a.attnum AS sort_order
            FROM pg_type t
            JOIN pg_class c ON t.typrelid = c.oid
            JOIN pg_attribute a ON a.attrelid = c.oid
            JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
            WHERE t.typtype = 'c' AND a.attnum > 0 AND NOT a.attisdropped AND n.nspname = ANY(?)
            ORDER BY type_oid, sort_order
        """

        private const val SQL_QUERY_OIDS_AND_ARRAYS = """
            SELECT
                t.oid AS type_oid, 
                n.nspname AS schema_name,
                t.typname AS type_name,
                t.typarray AS array_oid
            FROM pg_type t
            JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
            WHERE n.nspname = ANY(?)
            AND typarray != 0
        """
    }
}
