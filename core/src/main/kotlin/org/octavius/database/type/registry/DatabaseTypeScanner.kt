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
        // schema -> name -> data
        val enums = mutableMapOf<String, MutableMap<String, Triple<Int, Int, MutableList<String>>>>()
        val composites = mutableMapOf<String, MutableMap<String, Triple<Int, Int, MutableMap<String, Int>>>>()
        val allOidNames = mutableMapOf<Int, String>()

        try {
            val schemas = dbSchemas.toTypedArray()
            jdbcTemplate.query(SQL_QUERY_ALL_TYPES, { rs, _ ->
                val type = rs.getString("info_type")
                val schema = rs.getString("schema_name")
                val name = rs.getString("type_name")
                val oid = rs.getInt("type_oid")
                val arrayOid = rs.getInt("array_oid")
                val col1 = rs.getString("col1")

                // Cache names as we go
                allOidNames[oid] = "$schema.$name"
                if (arrayOid != 0) allOidNames[arrayOid] = "$schema.$name[]"

                when (type) {
                    "enum" -> {
                        val schemaEnums = enums.getOrPut(schema) { mutableMapOf() }
                        val triple = schemaEnums.getOrPut(name) { Triple(oid, arrayOid, mutableListOf()) }
                        triple.third.add(col1)
                    }
                    "composite" -> {
                        val col2 = rs.getInt("col2")
                        val schemaComposites = composites.getOrPut(schema) { mutableMapOf() }
                        val triple = schemaComposites.getOrPut(name) { Triple(oid, arrayOid, mutableMapOf()) }
                        triple.third[col1] = col2
                    }
                }
            }, schemas, schemas)

            // Also fetch names for ALL other types in pg_type (standard types etc.)
            jdbcTemplate.query(SQL_QUERY_OID_NAMES) { rs ->
                val oid = rs.getInt("oid")
                val name = rs.getString("typname")
                // Only put if not already present (we prefer schema-qualified names from previous query)
                allOidNames.putIfAbsent(oid, name)
            }
        } catch (e: Exception) {
            throw InitializationException(InitializationExceptionMessage.DB_QUERY_FAILED, cause = e,
                queryContext = QueryContext("", mapOf(), SQL_QUERY_ALL_TYPES, listOf(dbSchemas, dbSchemas)))
        }

        return DatabaseScanResult(
            enums.mapValues { schemaMap -> 
                schemaMap.value.mapValues { Triple(it.value.first, it.value.second, it.value.third.toList()) } 
            },
            composites.mapValues { schemaMap -> 
                schemaMap.value.mapValues { Triple(it.value.first, it.value.second, it.value.third.toMap()) } 
            },
            allOidNames
        )
    }

    fun fetchSearchPath(): List<String> {
        return try {
            val raw = jdbcTemplate.queryForObject("SHOW search_path", String::class.java) ?: ""
            raw.split(",").map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            logger.warn { "Failed to fetch search_path, falling back to default schemas. Error: ${e.message}" }
            dbSchemas
        }
    }

    companion object {
        val logger = KotlinLogging.logger {}

        private const val SQL_QUERY_ENUM_TYPES = """
            SELECT
                'enum' AS info_type,
                n.nspname AS schema_name,
                t.typname AS type_name,
                t.oid AS type_oid,
                t.typarray AS array_oid,
                e.enumlabel AS col1,
                NULL::int AS col2,
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
                n.nspname AS schema_name,
                t.typname AS type_name,
                t.oid AS type_oid,
                t.typarray AS array_oid,
                a.attname AS col1,
                a.atttypid::int AS col2, -- OID of attribute type
                a.attnum AS sort_order
            FROM
                pg_type t
                JOIN pg_class c ON t.typrelid = c.oid
                JOIN pg_attribute a ON a.attrelid = c.oid
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

        private const val SQL_QUERY_OID_NAMES = """
            SELECT oid, typname FROM pg_type
        """
    }
}
