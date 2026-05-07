package io.github.octaviusframework.db.core.type.registry

import io.github.octaviusframework.db.api.annotation.PgCompositeMapper
import io.github.octaviusframework.db.api.exception.InitializationException
import io.github.octaviusframework.db.api.exception.InitializationExceptionMessage
import io.github.octaviusframework.db.api.type.PgStandardType
import io.github.octaviusframework.db.api.type.QualifiedName
import io.github.octaviusframework.db.api.type.TypeHandler
import io.github.octaviusframework.db.api.util.CaseConverter
import io.github.octaviusframework.db.core.jdbc.JdbcTemplate
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
    dbSchemas: List<String>
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

        // Pre-compute lookup map for fast type resolution: Name -> Map<Schema, OID>
        val nameToSchemaOid = databaseData.allOidNames.entries
            .filter { !it.value.isArray }
            .groupBy({ it.value.name }, { it.value.schema to it.key })
            .mapValues { (_, list) -> list.toMap() }

        // Merge Enums and Composites
        val (finalEnums, enumClassMap) = mergeEnums(
            classpathData.enums, databaseData.enums, nameToSchemaOid, searchPath
        )
        val (finalComposites, compositeClassMap) = mergeComposites(
            classpathData.composites, databaseData.composites, nameToSchemaOid, searchPath
        )

        // Merge Standard and Custom Handlers
        val (handlersByOid, handlersByClass) = mergeHandlers(classpathData.customHandlers, searchPath, nameToSchemaOid)

        // 1. OID MAPS (Reversing allOidNames gives us mapping for ALL types: Base & Array)
        val pgNameToOidMap = databaseData.allOidNames.entries.associate { it.value to it.key }

        // 2. ARRAYS MAP (Only keep array definitions for types we actually registered)
        val registeredBaseOids =
            finalEnums.values.map { it.oid } + finalComposites.values.map { it.oid } + handlersByOid.keys

        val arraysByOid = databaseData.arrayOids
            .filter { it.value in registeredBaseOids } // Only create arrays for known elements
            .mapValues { (arrayOid, elementOid) ->
                val arrayQualifiedName = databaseData.allOidNames.getValue(arrayOid)
                PgArrayDefinition(arrayOid, arrayQualifiedName.toString(), elementOid)
            }

        // Build maps by OID for TypeRegistry
        val enumsByOid = finalEnums.values.associateBy { it.oid }
        val compositesByOid = finalComposites.values.associateBy { it.oid }

        // Category routing map
        val oidCategoryMap = buildOidCategoryMap(
            enumsByOid.keys, compositesByOid.keys, arraysByOid.keys,
            handlersByOid.keys, finalComposites
        )

        // Merge class maps
        val classToPgNameMap = enumClassMap + compositeClassMap

        logger.info { "TypeRegistry initialized. Enums: ${finalEnums.size}, Composites: ${finalComposites.size}, Arrays: ${arraysByOid.size}" }

        TypeRegistry(
            oidCategoryMap = oidCategoryMap,
            enumsByOid = enumsByOid,
            compositesByOid = compositesByOid,
            arraysByOid = arraysByOid,
            handlersByOid = handlersByOid,
            handlersByClass = handlersByClass,
            classToPgNameMap = classToPgNameMap,
            dynamicSerializers = classpathData.dynamicSerializers,
            classToDynamicNameMap = classpathData.dynamicReverseMap,
            pgNameToOidMap = pgNameToOidMap,
            oidToNameMap = databaseData.allOidNames
        )
    }

    private fun resolveOid(
        typeName: String,
        requestedSchema: String,
        searchPath: List<String>,
        nameToSchemaOid: Map<String, Map<String, Int>>
    ): Pair<Int, QualifiedName> {
        val schemasForName = nameToSchemaOid[typeName]
            ?: throw InitializationException(
                InitializationExceptionMessage.TYPE_DEFINITION_MISSING_IN_DB,
                details = "Type '$typeName' not found in any scanned schemas"
            )

        // 1. If schema is explicitly requested
        if (requestedSchema.isNotBlank()) {
            val oid = schemasForName[requestedSchema]
                ?: throw InitializationException(
                    InitializationExceptionMessage.TYPE_DEFINITION_MISSING_IN_DB,
                    details = "Type '$typeName' not found in requested schema '$requestedSchema'"
                )
            return oid to QualifiedName(requestedSchema, typeName)
        }

        // 2. If schema is empty, look in search_path (first match wins)
        for (schema in searchPath) {
            schemasForName[schema]?.let { oid -> return oid to QualifiedName(schema, typeName) }
        }

        // 3. If not in search_path, check for unambiguous match
        return when (schemasForName.size) {
            1 -> {
                val (schema, oid) = schemasForName.entries.first()
                oid to QualifiedName(schema, typeName)
            }

            else -> throw InitializationException(
                InitializationExceptionMessage.TYPE_DEFINITION_MISSING_IN_DB,
                details = "Type '$typeName' is ambiguous. Found in schemas: ${schemasForName.keys.joinToString()}. Please specify schema in annotation."
            )
        }
    }

    // -------------------------------------------------------------------------
    // MERGE & VALIDATE
    // -------------------------------------------------------------------------

    private fun mergeEnums(
        ktEnums: List<KtEnumInfo>,
        dbEnums: Map<Int, List<String>>,
        nameToSchemaOid: Map<String, Map<String, Int>>,
        searchPath: List<String>
    ): Pair<Map<QualifiedName, PgEnumDefinition>, Map<KClass<*>, QualifiedName>> {

        val definitions = mutableMapOf<QualifiedName, PgEnumDefinition>()
        val classMap = mutableMapOf<KClass<*>, QualifiedName>()

        ktEnums.forEach { kt ->
            val (oid, qualifiedName) = resolveOid(kt.pgName, kt.schema, searchPath, nameToSchemaOid)

            // Validate that DB actually sees this as an enum
            if (oid !in dbEnums) {
                throw InitializationException(
                    InitializationExceptionMessage.TYPE_DEFINITION_MISSING_IN_DB,
                    details = "Resolved type $qualifiedName is not an enum in the database."
                )
            }

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
            definitions[qualifiedName] = PgEnumDefinition(
                oid = oid,
                typeName = qualifiedName.toString(),
                valueToEnumMap = lookupMap,
                kClass = kt.kClass as KClass<out Enum<*>>
            )
            classMap[kt.kClass] = qualifiedName
        }

        return definitions to classMap
    }

    private fun mergeComposites(
        ktComposites: List<KtCompositeInfo>,
        dbComposites: Map<Int, Map<String, Int>>,
        nameToSchemaOid: Map<String, Map<String, Int>>,
        searchPath: List<String>
    ): Pair<Map<QualifiedName, PgCompositeDefinition>, Map<KClass<*>, QualifiedName>> {

        val definitions = mutableMapOf<QualifiedName, PgCompositeDefinition>()
        val classMap = mutableMapOf<KClass<*>, QualifiedName>()

        ktComposites.forEach { kt ->
            val (oid, qualifiedName) = resolveOid(kt.pgName, kt.schema, searchPath, nameToSchemaOid)

            val attributes = dbComposites[oid] ?: throw InitializationException(
                InitializationExceptionMessage.TYPE_DEFINITION_MISSING_IN_DB,
                details = "Resolved type $qualifiedName is not a composite type in the database."
            )

            val mapperInstance = kt.mapperClass?.let { mapperKClass ->
                try {
                    // Try to get object instance first (for Kotlin objects)
                    (mapperKClass.objectInstance ?: mapperKClass.java.getDeclaredConstructor()
                        .newInstance()) as PgCompositeMapper<Any>
                } catch (e: Exception) {
                    throw InitializationException(
                        InitializationExceptionMessage.INITIALIZATION_FAILED,
                        details = qualifiedName.toString(),
                        cause = IllegalStateException(
                            "Failed to instantiate mapper ${mapperKClass.qualifiedName}. Ensure it is an 'object' or has a public no-arg constructor.",
                            e
                        )
                    )
                }
            }

            definitions[qualifiedName] = PgCompositeDefinition(
                oid = oid,
                typeName = qualifiedName.toString(),
                attributes = attributes,
                kClass = kt.kClass,
                mapper = mapperInstance
            )
            classMap[kt.kClass] = qualifiedName
        }

        return definitions to classMap
    }

    private fun mergeHandlers(
        customHandlers: List<TypeHandler<*>>,
        searchPath: List<String>,
        nameToSchemaOid: Map<String, Map<String, Int>>
    ): Pair<Map<Int, TypeHandler<*>>, Map<KClass<*>, TypeHandler<*>>> {
        val standardHandlers = StandardTypeHandlers.createAll()
        val handlersByOid = mutableMapOf<Int, TypeHandler<*>>()
        val handlersByClass = mutableMapOf<KClass<*>, TypeHandler<*>>()

        standardHandlers.forEach { handler ->
            val oid = PgStandardType.entries.find { !it.isArray && it.typeName == handler.pgTypeName }!!.oid
            handlersByOid[oid] = handler
            handlersByClass[handler.kotlinClass] = handler
        }

        customHandlers.forEach { handler ->
            val (oid, _) = resolveOid(handler.pgTypeName, handler.pgSchema, searchPath, nameToSchemaOid)

            if (handlersByOid.containsKey(oid)) {
                logger.info { "Overriding default TypeHandler for PostgreSQL type '${handler.pgTypeName}' (OID: $oid) with custom handler: ${handler.kotlinClass.simpleName}" }
            } else if (handlersByClass.containsKey(handler.kotlinClass)) {
                logger.info { "Overriding default TypeHandler for Kotlin class '${handler.kotlinClass.simpleName}' with custom handler." }
            } else {
                logger.info { "Registered custom TypeHandler for '${handler.pgTypeName}' -> ${handler.kotlinClass.simpleName} (OID: $oid)" }
            }

            handlersByOid[oid] = handler
            handlersByClass[handler.kotlinClass] = handler
        }
        return (handlersByOid to handlersByClass)
    }

    // -------------------------------------------------------------------------
    // BUILD SUPPORTING STRUCTURES
    // -------------------------------------------------------------------------

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