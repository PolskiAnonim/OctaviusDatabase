package org.octavius.database.type.registry

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.octavius.data.exception.TypeRegistryException
import org.octavius.data.exception.TypeRegistryExceptionMessage
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
    private val jdbcTemplate: JdbcTemplate,
    private val packagesToScan: List<String>,
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

        // Standard and array types
        val standardTypes = PgStandardType.entries.filter { !it.isArray }.map { it.typeName }.toSet()
        val allBaseTypes = finalEnums.keys + finalComposites.keys + standardTypes
        val finalArrays = buildArrays(allBaseTypes)

        // Category routing map
        val categoryMap = buildCategoryMap(finalEnums.keys, finalComposites.keys, finalArrays.keys, standardTypes)

        // Merge class maps
        val classToPgNameMap = enumClassMap + compositeClassMap

        logger.info { "TypeRegistry initialized. Enums: ${finalEnums.size}, Composites: ${finalComposites.size}, Arrays: ${finalArrays.size}" }

        TypeRegistry(
            categoryMap = categoryMap,
            enums = finalEnums,
            composites = finalComposites,
            arrays = finalArrays,
            classToPgNameMap = classToPgNameMap,
            dynamicSerializers = classpathData.dynamicSerializers,
            classToDynamicNameMap = classpathData.dynamicReverseMap
        )
    }

    // -------------------------------------------------------------------------
    // MERGE & VALIDATE
    // -------------------------------------------------------------------------

    private fun mergeEnums(
        ktEnums: List<KtEnumInfo>,
        dbEnums: Map<String, List<String>>
    ): Pair<Map<String, PgEnumDefinition>, Map<KClass<*>, String>> {

        val definitions = mutableMapOf<String, PgEnumDefinition>()
        val classMap = mutableMapOf<KClass<*>, String>()

        ktEnums.forEach { kt ->
            // Validate: enum must exist in database
            dbEnums[kt.pgName]
                ?: throw TypeRegistryException(
                    messageEnum = TypeRegistryExceptionMessage.TYPE_DEFINITION_MISSING_IN_DB,
                    typeName = kt.pgName,
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
        dbComposites: Map<String, Map<String, String>>
    ): Pair<Map<String, PgCompositeDefinition>, Map<KClass<*>, String>> {

        val definitions = mutableMapOf<String, PgCompositeDefinition>()
        val classMap = mutableMapOf<KClass<*>, String>()

        ktComposites.forEach { kt ->
            // Validate: composite must exist in database
            val dbAttributes = dbComposites[kt.pgName]
                ?: throw TypeRegistryException(
                    messageEnum = TypeRegistryExceptionMessage.TYPE_DEFINITION_MISSING_IN_DB,
                    typeName = kt.pgName,
                    cause = IllegalStateException("Class '${kt.kClass.qualifiedName}' expects DB type '${kt.pgName}'")
                )

            definitions[kt.pgName] = PgCompositeDefinition(
                typeName = kt.pgName,
                attributes = dbAttributes,
                kClass = kt.kClass
            )
            classMap[kt.kClass] = kt.pgName
        }

        return definitions to classMap
    }

    // -------------------------------------------------------------------------
    // BUILD SUPPORTING STRUCTURES
    // -------------------------------------------------------------------------

    private fun buildArrays(baseTypes: Set<String>): Map<String, PgArrayDefinition> {
        return baseTypes.associate { base ->
            val arrayName = "_$base"
            arrayName to PgArrayDefinition(arrayName, base)
        }
    }

    private fun buildCategoryMap(
        enums: Set<String>,
        composites: Set<String>,
        arrays: Set<String>,
        standard: Set<String>
    ): Map<String, TypeCategory> {
        val map = mutableMapOf<String, TypeCategory>()

        enums.forEach { map[it] = TypeCategory.ENUM }
        composites.forEach {
            // "dynamic_dto" is treated specially during deserialization
            map[it] = if (it == "dynamic_dto") TypeCategory.DYNAMIC else TypeCategory.COMPOSITE
        }
        arrays.forEach { map[it] = TypeCategory.ARRAY }
        standard.forEach { map[it] = TypeCategory.STANDARD }

        return map
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}