package org.octavius.database.type.registry

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.octavius.data.exception.InitializationException
import org.octavius.data.exception.InitializationExceptionMessage
import org.octavius.data.annotation.PgCompositeMapper
import org.octavius.data.type.DYNAMIC_DTO
import org.octavius.data.type.PgStandardType
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
    jdbcTemplate: JdbcTemplate,
    packagesToScan: List<String>,
    private val dbSchemas: List<String>
) {
    private val classpathScanner = ClasspathTypeScanner(packagesToScan)
    private val databaseScanner = DatabaseTypeScanner(jdbcTemplate, dbSchemas)

    /**
     * Loads and builds the TypeRegistry.
     *
     * 1. Scans classpath and database in parallel
     * 2. Merges and validates type definitions
     * 3. Builds supporting structures (arrays, category map)
     */
    fun load(): TypeRegistry = runBlocking {
        logger.info { "Starting TypeRegistry initialization..." }

        // Parallel data fetching
        val classpathJob = async(Dispatchers.IO) { classpathScanner.scan() }
        val databaseJob = async(Dispatchers.IO) { databaseScanner.scan() }

        val classpathData = classpathJob.await()
        val databaseData = databaseJob.await()

        logger.debug { "Merging definitions..." }

        // Merge with validation (for types existing in code there must be their counterpart in database)
        val (finalEnums, enumClassMap) = mergeEnums(classpathData.enums, databaseData.enums)
        val (finalComposites, compositeClassMap) = mergeComposites(classpathData.composites, databaseData.composites)

        // Standard types OIDs
        val standardOids = PgStandardType.entries.associate { it.typeName to it.oid }

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
            pgNameToOidMap = pgNameToOidMap
        )
    }

    // -------------------------------------------------------------------------
    // MERGE & VALIDATE
    // -------------------------------------------------------------------------

    private fun mergeEnums(
        ktEnums: List<KtEnumInfo>,
        dbEnums: Map<String, Triple<Int, Int, List<String>>>
    ): Pair<Map<String, PgEnumDefinition>, Map<KClass<*>, String>> {

        val definitions = mutableMapOf<String, PgEnumDefinition>()
        val classMap = mutableMapOf<KClass<*>, String>()

        ktEnums.forEach { kt ->
            // Validate: enum must exist in database
            val dbInfo = dbEnums[kt.pgName]
                ?: throw InitializationException(
                    messageEnum = InitializationExceptionMessage.TYPE_DEFINITION_MISSING_IN_DB,
                    details = kt.pgName,
                    cause = IllegalStateException("Class '${kt.kClass.qualifiedName}' expects DB type '${kt.pgName}'")
                )

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

            definitions[kt.pgName] = PgEnumDefinition(
                oid = dbInfo.first,
                typeName = kt.pgName,
                valueToEnumMap = lookupMap,
                kClass = enumClassTyped
            )
            classMap[kt.kClass] = kt.pgName
        }

        return definitions to classMap
    }

    private fun mergeComposites(
        ktComposites: List<KtCompositeInfo>,
        dbComposites: Map<String, Triple<Int, Int, Map<String, Int>>>
    ): Pair<Map<String, PgCompositeDefinition>, Map<KClass<*>, String>> {

        val definitions = mutableMapOf<String, PgCompositeDefinition>()
        val classMap = mutableMapOf<KClass<*>, String>()

        ktComposites.forEach { kt ->
            // Validate: composite must exist in database
            val dbInfo = dbComposites[kt.pgName]
                ?: throw InitializationException(
                    messageEnum = InitializationExceptionMessage.TYPE_DEFINITION_MISSING_IN_DB,
                    details = kt.pgName,
                    cause = IllegalStateException("Class '${kt.kClass.qualifiedName}' expects DB type '${kt.pgName}'")
                )

            val mapperInstance = kt.mapperClass?.let { mapperKClass ->
                try {
                    // Try to get object instance first (for Kotlin objects)
                    (mapperKClass.objectInstance ?: mapperKClass.java.getDeclaredConstructor().newInstance()) as PgCompositeMapper<Any>
                } catch (e: Exception) {
                    throw InitializationException(
                        InitializationExceptionMessage.INITIALIZATION_FAILED,
                        details = kt.pgName,
                        cause = IllegalStateException("Failed to instantiate mapper ${mapperKClass.qualifiedName}. Ensure it is an 'object' or has a public no-arg constructor.", e)
                    )
                }
            }

            definitions[kt.pgName] = PgCompositeDefinition(
                oid = dbInfo.first,
                typeName = kt.pgName,
                attributes = dbInfo.third,
                kClass = kt.kClass,
                mapper = mapperInstance
            )
            classMap[kt.kClass] = kt.pgName
        }

        return definitions to classMap
    }

    // -------------------------------------------------------------------------
    // BUILD SUPPORTING STRUCTURES
    // -------------------------------------------------------------------------

    private fun buildArrayAndOidMaps(
        enums: Map<String, PgEnumDefinition>,
        composites: Map<String, PgCompositeDefinition>,
        standardOids: Map<String, Int>,
        dbEnums: Map<String, Triple<Int, Int, List<String>>>,
        dbComposites: Map<String, Triple<Int, Int, Map<String, Int>>>
    ): Pair<Map<String, PgArrayDefinition>, Map<String, Int>> {
        val arrayDefinitions = mutableMapOf<String, PgArrayDefinition>()
        val pgNameToOidMap = mutableMapOf<String, Int>()

        // Add standard types to name->oid map
        pgNameToOidMap.putAll(standardOids)

        // Handle Enums
        enums.forEach { (name, def) ->
            pgNameToOidMap[name] = def.oid
            val arrayOid = dbEnums[name]?.second ?: 0
            if (arrayOid != 0) {
                val arrayName = "_$name"
                arrayDefinitions[arrayName] = PgArrayDefinition(arrayOid, arrayName, def.oid)
                pgNameToOidMap[arrayName] = arrayOid
            }
        }

        // Handle Composites
        composites.forEach { (name, def) ->
            pgNameToOidMap[name] = def.oid
            val arrayOid = dbComposites[name]?.second ?: 0
            if (arrayOid != 0) {
                val arrayName = "_$name"
                arrayDefinitions[arrayName] = PgArrayDefinition(arrayOid, arrayName, def.oid)
                pgNameToOidMap[arrayName] = arrayOid
            }
        }

        // Handle Standard Array Types (they are already in standardOids)
        PgStandardType.entries.filter { it.isArray }.forEach { pgType ->
            val baseName = pgType.typeName.substring(1) // remove '_'
            val elementOid = standardOids[baseName] ?: 0
            if (elementOid != 0) {
                arrayDefinitions[pgType.typeName] = PgArrayDefinition(pgType.oid, pgType.typeName, elementOid)
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
        finalComposites: Map<String, PgCompositeDefinition>
    ): Map<Int, TypeCategory> {
        val map = mutableMapOf<Int, TypeCategory>()

        val dynamicDtoOid = finalComposites[DYNAMIC_DTO]?.oid

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
    }
}
