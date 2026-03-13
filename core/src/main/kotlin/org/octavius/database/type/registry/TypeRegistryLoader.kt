package org.octavius.database.type.registry

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.octavius.data.annotation.PgCompositeMapper
import org.octavius.data.exception.InitializationException
import org.octavius.data.exception.InitializationExceptionMessage
import org.octavius.data.type.PgStandardType
import org.octavius.data.type.QualifiedName
import org.octavius.data.util.CaseConverter
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.reflect.KClass

/**
 * Orchestrates TypeRegistry initialization by coordinating classpath and database scanning,
 * then merging and validating the results.
 *
 * Uses [ClasspathTypeScanner] to discover annotated Kotlin types and [DatabaseTypeScanner]
 * to fetch PostgreSQL type definitions. Both scans run in parallel for performance.
 */
internal class TypeRegistryLoader(
    private val jdbcTemplate: JdbcTemplate,
    packagesToScan: List<String>,
    private val dbSchemas: List<String>
) {
    private val classpathScanner = ClasspathTypeScanner(packagesToScan)
    private val databaseScanner = DatabaseTypeScanner(jdbcTemplate, dbSchemas)

    fun load(): TypeRegistry = runBlocking {
        logger.info { "Starting TypeRegistry initialization..." }

        // Parallel data fetching
        val classpathJob = async(Dispatchers.IO) { classpathScanner.scan() }
        val databaseJob = async(Dispatchers.IO) { databaseScanner.scan() }
        val searchPathJob = async(Dispatchers.IO) { databaseScanner.fetchSearchPath() }

        val classpathData = classpathJob.await()
        val databaseData = databaseJob.await()
        val searchPath = searchPathJob.await()

        logger.debug { "Merging definitions using search_path: ${searchPath.joinToString()}" }

        // Merge with validation
        val (finalEnums, enumClassMap) = mergeEnums(classpathData.enums, databaseData.enums, searchPath)
        val (finalComposites, compositeClassMap) = mergeComposites(classpathData.composites, databaseData.composites, searchPath)

        // Standard types OIDs (map to empty schema for clean SQL casting)
        val standardOids = PgStandardType.entries.filter { !it.isArray }.associate { 
            QualifiedName("", it.typeName) to it.oid 
        }

        // Build array definitions and OID maps
        val (finalArrays, pgNameToOidMap) = buildArrayAndOidMaps(
            finalEnums, finalComposites, standardOids, databaseData.enums, databaseData.composites
        )

        // Build maps by OID for TypeRegistry
        val enumsByOid = finalEnums.values.associateBy { it.oid }
        val compositesByOid = finalComposites.values.associateBy { it.oid }
        val arraysByOid = finalArrays.values.associateBy { it.oid }

        // Category routing map (now by OID)
        val standardNonArrayOids = PgStandardType.entries.filter { !it.isArray }.map { it.oid }.toSet()
        val oidCategoryMap = buildOidCategoryMap(
            enumsByOid.keys, compositesByOid.keys, arraysByOid.keys, 
            standardNonArrayOids, finalComposites
        )

        // Merge class maps
        val classToPgNameMap = enumClassMap + compositeClassMap

        // Procedures
        val finalProcedures = buildProcedures(databaseData.procedures)

        logger.info { "TypeRegistry initialized. Enums: ${finalEnums.size}, Composites: ${finalComposites.size}, Arrays: ${finalArrays.size}, Procedures: ${finalProcedures.size}" }

        TypeRegistry(
            oidCategoryMap = oidCategoryMap,
            enumsByOid = enumsByOid,
            compositesByOid = compositesByOid,
            arraysByOid = arraysByOid,
            procedures = finalProcedures,
            classToPgNameMap = classToPgNameMap,
            dynamicSerializers = classpathData.dynamicSerializers,
            classToDynamicNameMap = classpathData.dynamicReverseMap,
            pgNameToOidMap = pgNameToOidMap,
            oidToNameMap = databaseData.allOidNames
        )
    }

    private fun <T> resolveType(
        typeName: String,
        requestedSchema: String,
        searchPath: List<String>,
        dbData: Map<String, Map<String, T>>
    ): Pair<String, T> {
        // 1. If schema is explicitly requested
        if (requestedSchema.isNotBlank()) {
            val schemaData = dbData[requestedSchema]
                ?: throw InitializationException(InitializationExceptionMessage.TYPE_DEFINITION_MISSING_IN_DB, details = "Schema '$requestedSchema' not found during scan")
            val data = schemaData[typeName]
                ?: throw InitializationException(InitializationExceptionMessage.TYPE_DEFINITION_MISSING_IN_DB, details = "Type '$typeName' not found in schema '$requestedSchema'")
            return requestedSchema to data
        }

        // 2. If schema is empty, look in search_path (first match wins)
        for (schema in searchPath) {
            val data = dbData[schema]?.get(typeName)
            if (data != null) return schema to data
        }

        // 3. If not in search_path, check all scanned schemas for unambiguous match
        val matches = dbData.filter { it.value.containsKey(typeName) }
        return when (matches.size) {
            0 -> throw InitializationException(InitializationExceptionMessage.TYPE_DEFINITION_MISSING_IN_DB, details = "Type '$typeName' not found in any scanned schemas")
            1 -> {
                val entry = matches.entries.first()
                entry.key to entry.value[typeName]!!
            }
            else -> throw InitializationException(InitializationExceptionMessage.TYPE_DEFINITION_MISSING_IN_DB, details = "Type '$typeName' is ambiguous. Found in schemas: ${matches.keys.joinToString()}. Please specify schema in annotation.")
        }
    }

    // -------------------------------------------------------------------------
    // MERGE & VALIDATE
    // -------------------------------------------------------------------------

    private fun mergeEnums(
        ktEnums: List<KtEnumInfo>,
        dbEnums: Map<String, Map<String, Triple<Int, Int, List<String>>>>,
        searchPath: List<String>
    ): Pair<Map<QualifiedName, PgEnumDefinition>, Map<KClass<*>, QualifiedName>> {

        val definitions = mutableMapOf<QualifiedName, PgEnumDefinition>()
        val classMap = mutableMapOf<KClass<*>, QualifiedName>()

        ktEnums.forEach { kt ->
            val (resolvedSchema, dbInfo) = resolveType(kt.pgName, kt.schema, searchPath, dbEnums)
            
            val qualifiedName = QualifiedName(resolvedSchema, kt.pgName)

            val enumConstants = kt.kClass.java.enumConstants!!

            // Build lookup map: DB_STRING -> ENUM_INSTANCE
            val lookupMap: Map<String, Enum<*>> = enumConstants.associate { constant ->
                val enumConst = constant as Enum<*>
                val dbKey = CaseConverter.convert(
                    value = enumConst.name,
                    from = kt.kotlinConvention,
                    to = kt.pgConvention
                )
                dbKey to enumConst
            }

            @Suppress("UNCHECKED_CAST")
            val enumClassTyped = kt.kClass as KClass<out Enum<*>>

            definitions[qualifiedName] = PgEnumDefinition(
                oid = dbInfo.first,
                typeName = qualifiedName.toString(),
                valueToEnumMap = lookupMap,
                kClass = enumClassTyped
            )
            classMap[kt.kClass] = qualifiedName
        }

        return definitions to classMap
    }

    private fun mergeComposites(
        ktComposites: List<KtCompositeInfo>,
        dbComposites: Map<String, Map<String, Triple<Int, Int, Map<String, Int>>>>,
        searchPath: List<String>
    ): Pair<Map<QualifiedName, PgCompositeDefinition>, Map<KClass<*>, QualifiedName>> {

        val definitions = mutableMapOf<QualifiedName, PgCompositeDefinition>()
        val classMap = mutableMapOf<KClass<*>, QualifiedName>()

        ktComposites.forEach { kt ->
            val (resolvedSchema, dbInfo) = resolveType(kt.pgName, kt.schema, searchPath, dbComposites)
            
            val qualifiedName = QualifiedName(resolvedSchema, kt.pgName)

            val mapperInstance = kt.mapperClass?.let { mapperKClass ->
                try {
                    // Try to get object instance first (for Kotlin objects)
                    (mapperKClass.objectInstance ?: mapperKClass.java.getDeclaredConstructor().newInstance()) as PgCompositeMapper<Any>
                } catch (e: Exception) {
                    throw InitializationException(
                        InitializationExceptionMessage.INITIALIZATION_FAILED,
                        details = qualifiedName.toString(),
                        cause = IllegalStateException("Failed to instantiate mapper ${mapperKClass.qualifiedName}. Ensure it is an 'object' or has a public no-arg constructor.", e)
                    )
                }
            }

            definitions[qualifiedName] = PgCompositeDefinition(
                oid = dbInfo.first,
                typeName = qualifiedName.toString(),
                attributes = dbInfo.third,
                kClass = kt.kClass,
                mapper = mapperInstance
            )
            classMap[kt.kClass] = qualifiedName
        }

        return definitions to classMap
    }

    // -------------------------------------------------------------------------
    // BUILD SUPPORTING STRUCTURES
    // -------------------------------------------------------------------------

    private fun buildArrayAndOidMaps(
        enums: Map<QualifiedName, PgEnumDefinition>,
        composites: Map<QualifiedName, PgCompositeDefinition>,
        standardOids: Map<QualifiedName, Int>,
        dbEnums: Map<String, Map<String, Triple<Int, Int, List<String>>>>,
        dbComposites: Map<String, Map<String, Triple<Int, Int, Map<String, Int>>>>
    ): Pair<Map<QualifiedName, PgArrayDefinition>, Map<QualifiedName, Int>> {
        val arrayDefinitions = mutableMapOf<QualifiedName, PgArrayDefinition>()
        val pgNameToOidMap = mutableMapOf<QualifiedName, Int>()

        // Add standard types to name->oid map
        pgNameToOidMap.putAll(standardOids)

        // Handle Enums
        enums.forEach { (qualifiedName, def) ->
            pgNameToOidMap[qualifiedName] = def.oid
            
            val arrayOid = dbEnums[qualifiedName.schema]?.get(qualifiedName.name)?.second ?: 0
            if (arrayOid != 0) {
                val arrayQualifiedName = qualifiedName.asArray()
                arrayDefinitions[arrayQualifiedName] = PgArrayDefinition(arrayOid, arrayQualifiedName.toString(), def.oid)
                pgNameToOidMap[arrayQualifiedName] = arrayOid
            }
        }

        // Handle Composites
        composites.forEach { (qualifiedName, def) ->
            pgNameToOidMap[qualifiedName] = def.oid
            
            val arrayOid = dbComposites[qualifiedName.schema]?.get(qualifiedName.name)?.second ?: 0
            if (arrayOid != 0) {
                val arrayQualifiedName = qualifiedName.asArray()
                arrayDefinitions[arrayQualifiedName] = PgArrayDefinition(arrayOid, arrayQualifiedName.toString(), def.oid)
                pgNameToOidMap[arrayQualifiedName] = arrayOid
            }
        }

        // Handle Standard Array Types
        PgStandardType.entries.filter { it.isArray }.forEach { pgType ->
            val baseName = pgType.typeName.removeSuffix("[]")
            val elementOid = standardOids[QualifiedName("", baseName)] ?: 0
            if (elementOid != 0) {
                val arrayQualifiedName = QualifiedName("", baseName, isArray = true)
                arrayDefinitions[arrayQualifiedName] = PgArrayDefinition(pgType.oid, arrayQualifiedName.toString(), elementOid)
                pgNameToOidMap[arrayQualifiedName] = pgType.oid
            }
        }

        return arrayDefinitions to pgNameToOidMap
    }

    private fun buildProcedures(
        dbProcedures: Map<String, List<PgProcedureParam>>
    ): Map<String, PgProcedureDefinition> {
        return dbProcedures.map { (name, params) ->
            name to PgProcedureDefinition(name = name, params = params)
        }.toMap()
    }

    private fun buildOidCategoryMap(
        enums: Set<Int>,
        composites: Set<Int>,
        arrays: Set<Int>,
        standard: Set<Int>,
        finalComposites: Map<QualifiedName, PgCompositeDefinition>
    ): Map<Int, TypeCategory> {
        val map = mutableMapOf<Int, TypeCategory>()

        val dynamicDtoOid = finalComposites[DYNAMIC_DTO_QUALIFIED_NAME]?.oid

        enums.forEach { map[it] = TypeCategory.ENUM }
        composites.forEach { oid ->
            map[oid] = if (oid == dynamicDtoOid) TypeCategory.DYNAMIC else TypeCategory.COMPOSITE
        }
        arrays.forEach { map[it] = TypeCategory.ARRAY }
        standard.forEach { map[it] = TypeCategory.STANDARD }

        return map
    }

    companion object {
        private val logger = KotlinLogging.logger {}
        private val DYNAMIC_DTO_QUALIFIED_NAME = QualifiedName("public", "dynamic_dto")
    }
}
