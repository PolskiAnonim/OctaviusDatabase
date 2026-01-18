package org.octavius.database.type.registry

import io.github.classgraph.AnnotationEnumValue
import io.github.classgraph.ClassGraph
import io.github.classgraph.ScanResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.octavius.data.annotation.DynamicallyMappable
import org.octavius.data.annotation.PgComposite
import org.octavius.data.annotation.PgEnum
import org.octavius.data.exception.TypeRegistryException
import org.octavius.data.exception.TypeRegistryExceptionMessage
import org.octavius.data.type.PgStandardType
import org.octavius.data.util.CaseConvention
import org.octavius.data.util.CaseConverter
import org.octavius.data.util.toSnakeCase
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.reflect.KClass

internal class TypeRegistryLoader(
    private val jdbcTemplate: JdbcTemplate,
    private val packagesToScan: List<String>,
    private val dbSchemas: List<String>
) {
    // --- Internal loader DTOs ---
    private data class ClasspathData(
        val enums: List<KtEnumInfo>,
        val composites: List<KtCompositeInfo>,
        val dynamicSerializers: Map<String, KSerializer<Any>>,
        val dynamicReverseMap: Map<KClass<*>, String>
    )
    private data class KtEnumInfo(val kClass: KClass<*>, val pgName: String, val pgConv: CaseConvention, val ktConv: CaseConvention)
    private data class KtCompositeInfo(val kClass: KClass<*>, val pgName: String)

    // Results from database
    private data class DatabaseData(
        val enums: Map<String, List<String>>, // TypeName -> Values
        val composites: Map<String, Map<String, String>> // TypeName -> (Col -> Type) [Ordered Map]
    )

    fun load(): TypeRegistry = runBlocking {
        logger.info { "Starting TypeRegistry initialization..." }

        // 1. Parallel data fetching
        val cpJob = async(Dispatchers.IO) { scanClasspath() }
        val dbJob = async(Dispatchers.IO) { scanDatabase() }

        val cpData = cpJob.await()
        val dbData = dbJob.await()

        logger.debug { "Merging definitions..." }

        // 2. Building maps with "Strict" validation (Must be in both code AND database)
        val (finalEnums, enumClassMap) = mergeEnums(cpData.enums, dbData.enums)
        val (finalComposites, compositeClassMap) = mergeComposites(cpData.composites, dbData.composites)

        // 3. Standard and array types
        val standardTypes = PgStandardType.entries.filter { !it.isArray }.map { it.typeName }.toSet()

        // Generate arrays for everything we have registered
        val allBaseTypes = finalEnums.keys + finalComposites.keys + standardTypes
        val finalArrays = buildArrays(allBaseTypes)

        // 4. Building router (TypeCategory Map)
        val categoryMap = buildCategoryMap(finalEnums.keys, finalComposites.keys, finalArrays.keys, standardTypes)

        // 5. Merging class maps
        val classToPgNameMap = enumClassMap + compositeClassMap

        logger.info { "TypeRegistry initialized. Enums: ${finalEnums.size}, Composites: ${finalComposites.size}, Arrays: ${finalArrays.size}" }

        TypeRegistry(
            categoryMap = categoryMap,
            enums = finalEnums,
            composites = finalComposites,
            arrays = finalArrays,
            classToPgNameMap = classToPgNameMap,
            dynamicSerializers = cpData.dynamicSerializers,
            classToDynamicNameMap = cpData.dynamicReverseMap
        )
    }

    // -------------------------------------------------------------------------
    // STAGE 1: CLASSPATH (Annotation scanning)
    // -------------------------------------------------------------------------

    private fun scanClasspath(): ClasspathData {
        val enumInfos = mutableListOf<KtEnumInfo>()
        val compositeInfos = mutableListOf<KtCompositeInfo>()
        val targetSerializers = mutableMapOf<String, KSerializer<Any>>()
        val targetReverseMap = mutableMapOf<KClass<*>, String>()

        // Set for tracking uniqueness of database type names (Enums + Composites share namespace in PG)
        val seenPgNames = mutableSetOf<String>()

        try {
            logger.debug { "Scanning packages for annotations: ${packagesToScan.joinToString()}" }
            ClassGraph()
                .enableAllInfo()                    // DynamicDto
                .acceptPackages("org.octavius.data.type", *packagesToScan.toTypedArray())
                .scan().use { result ->
                    processEnums(result, enumInfos, seenPgNames)

                    processComposites(result, compositeInfos, seenPgNames)

                    processDynamicTypes(result, targetSerializers, targetReverseMap)
                }
        } catch (e: TypeRegistryException) {
            throw e // Pass through our exceptions
        } catch (e: Exception) {
            throw TypeRegistryException(TypeRegistryExceptionMessage.CLASSPATH_SCAN_FAILED, cause = e)
        }

        return ClasspathData(enumInfos, compositeInfos, targetSerializers, targetReverseMap)
    }


    private fun processEnums(scanResult: ScanResult, target: MutableList<KtEnumInfo>, seenNames: MutableSet<String>) {
        scanResult.getClassesWithAnnotation(PgEnum::class.java).forEach { classInfo ->
            if(!classInfo.isEnum) throw TypeRegistryException(TypeRegistryExceptionMessage.INITIALIZATION_FAILED, typeName = classInfo.name, cause = IllegalStateException("@PgEnum not on enum"))

            val annotation = classInfo.getAnnotationInfo(PgEnum::class.java)
            val name = (annotation.parameterValues.getValue("name") as String).ifBlank { classInfo.simpleName.toSnakeCase() }

            // Check for duplicates
            if (!seenNames.add(name)) {
                throw TypeRegistryException(
                    messageEnum = TypeRegistryExceptionMessage.DUPLICATE_PG_TYPE_DEFINITION,
                    typeName = name,
                    cause = IllegalStateException("Duplicate PostgreSQL type name detected: '$name'. Found on ${classInfo.name}")
                )
            }

            val pgConv = (annotation.parameterValues.getValue("pgConvention") as AnnotationEnumValue)
                .loadClassAndReturnEnumValue() as CaseConvention
            val ktConv = (annotation.parameterValues.getValue("kotlinConvention") as AnnotationEnumValue)
                .loadClassAndReturnEnumValue() as CaseConvention
            val kClass = classInfo.loadClass().kotlin
            target.add(KtEnumInfo(kClass, name, pgConv, ktConv))
        }
    }

    private fun processComposites(scanResult: ScanResult, target: MutableList<KtCompositeInfo>, seenNames: MutableSet<String>) {
        scanResult.getClassesWithAnnotation(PgComposite::class.java).forEach { classInfo ->
            if(classInfo.isEnum) throw TypeRegistryException(TypeRegistryExceptionMessage.INITIALIZATION_FAILED, typeName = classInfo.name, cause = IllegalStateException("@PgComposite on enum"))

            val annotation = classInfo.getAnnotationInfo(PgComposite::class.java)
            val name = (annotation.parameterValues.getValue("name") as String).ifBlank { classInfo.simpleName.toSnakeCase() }

            // Check for duplicates (shared pool with Enums)
            if (!seenNames.add(name)) {
                throw TypeRegistryException(
                    messageEnum = TypeRegistryExceptionMessage.DUPLICATE_PG_TYPE_DEFINITION,
                    typeName = name,
                    cause = IllegalStateException("Duplicate PostgreSQL type name detected: '$name'. Found on ${classInfo.name}")
                )
            }

            val kClass = classInfo.loadClass().kotlin
            target.add(KtCompositeInfo(kClass, name))
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun processDynamicTypes(
        scanResult: ScanResult,
        targetSerializers: MutableMap<String, KSerializer<Any>>,
        targetReverseMap: MutableMap<KClass<*>, String>
    ) {
        scanResult.getClassesWithAnnotation(DynamicallyMappable::class.java).forEach { classInfo ->
            if (!classInfo.hasAnnotation("kotlinx.serialization.Serializable")) {
                throw TypeRegistryException(TypeRegistryExceptionMessage.INITIALIZATION_FAILED, typeName = classInfo.name, cause = IllegalStateException("Missing @Serializable"))
            }

            val annotation = classInfo.getAnnotationInfo(DynamicallyMappable::class.java)
            val typeName = annotation.parameterValues.getValue("typeName") as String

            // Check for duplicate DynamicDTO keys
            if (targetSerializers.containsKey(typeName)) {
                throw TypeRegistryException(
                    messageEnum = TypeRegistryExceptionMessage.DUPLICATE_DYNAMIC_TYPE_DEFINITION,
                    typeName = typeName,
                    cause = IllegalStateException("Duplicate @DynamicallyMappable key: '$typeName'. Found on ${classInfo.name}")
                )
            }

            val kClass = classInfo.loadClass().kotlin
            try {
                @Suppress("UNCHECKED_CAST")
                val serializer = kClass.serializer() as KSerializer<Any>

                targetSerializers[typeName] = serializer
                targetReverseMap[kClass] = typeName

                logger.trace { "Registered DynamicDTO serializer for '$typeName' -> ${kClass.simpleName}" }
            } catch (e: Exception) {
                // If the class is broken (e.g., generic without context), we know immediately.
                throw TypeRegistryException(
                    TypeRegistryExceptionMessage.INITIALIZATION_FAILED,
                    typeName = typeName,
                    cause = IllegalStateException("Failed to obtain serializer for ${kClass.qualifiedName}. Ensure it is a valid @Serializable class/enum.", e)
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // STAGE 2: DATABASE (Fetching definitions)
    // -------------------------------------------------------------------------

    private fun scanDatabase(): DatabaseData {
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
            throw TypeRegistryException(TypeRegistryExceptionMessage.DB_QUERY_FAILED, cause = e)
        }
        return DatabaseData(enums, composites)
    }

    // -------------------------------------------------------------------------
    // STAGE 3: MERGE & VALIDATE (Strict Mode)
    // -------------------------------------------------------------------------

    private fun mergeEnums(
        ktEnums: List<KtEnumInfo>,
        dbEnums: Map<String, List<String>>
    ): Pair<Map<String, PgEnumDefinition>, Map<KClass<*>, String>> {

        val defs = mutableMapOf<String, PgEnumDefinition>()
        val classMap = mutableMapOf<KClass<*>, String>()

        ktEnums.forEach { kt ->
            // VALIDATION: Check if Enum exists in database
            dbEnums[kt.pgName] ?: // Type declared in code but missing in database -> Critical error
            throw TypeRegistryException(
                messageEnum = TypeRegistryExceptionMessage.TYPE_DEFINITION_MISSING_IN_DB,
                typeName = kt.pgName,
                cause = IllegalStateException("Class '${kt.kClass.qualifiedName}' expects DB type '${kt.pgName}'")
            )

            // Get all enum constants once at startup
            val enumConstants = kt.kClass.java.enumConstants!!

            // Build map: DB_STRING -> ENUM_INSTANCE
            val lookupMap: Map<String, Enum<*>> = enumConstants.associate { constant ->
                val enumConst = constant as Enum<*>

                // Name conversion (Kotlin -> DB)
                val dbKey = CaseConverter.convert(
                    value = enumConst.name,
                    from = kt.ktConv,
                    to = kt.pgConv
                )

                // Return pair (Key, Value). Value is now of type Enum<*>
                dbKey to enumConst
            }

            @Suppress("UNCHECKED_CAST") val enumClassTyped = kt.kClass as KClass<out Enum<*>>

            defs[kt.pgName] = PgEnumDefinition(
                typeName = kt.pgName,
                valueToEnumMap = lookupMap,
                kClass = enumClassTyped
            )
            classMap[kt.kClass] = kt.pgName
        }
        return defs to classMap
    }

    private fun mergeComposites(
        ktComposites: List<KtCompositeInfo>,
        dbComposites: Map<String, Map<String, String>>
    ): Pair<Map<String, PgCompositeDefinition>, Map<KClass<*>, String>> {

        val defs = mutableMapOf<String, PgCompositeDefinition>()
        val classMap = mutableMapOf<KClass<*>, String>()

        ktComposites.forEach { kt ->
            val dbAttributes =
                dbComposites[kt.pgName] ?: // Type declared in code but missing in database -> Critical error
                throw TypeRegistryException(
                    messageEnum = TypeRegistryExceptionMessage.TYPE_DEFINITION_MISSING_IN_DB,
                    typeName = kt.pgName,
                    cause = IllegalStateException("Class '${kt.kClass.qualifiedName}' expects DB type '${kt.pgName}'")
                )

            defs[kt.pgName] = PgCompositeDefinition(
                typeName = kt.pgName,
                attributes = dbAttributes, // Map preserves order from DB
                kClass = kt.kClass
            )
            classMap[kt.kClass] = kt.pgName
        }
        return defs to classMap
    }

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
            // If structure is named "dynamic_dto", we treat it specially during deserialization.
            map[it] = if (it == "dynamic_dto") TypeCategory.DYNAMIC else TypeCategory.COMPOSITE
        }

        arrays.forEach { map[it] = TypeCategory.ARRAY }
        standard.forEach { map[it] = TypeCategory.STANDARD }
        return map
    }

    companion object {
        private val logger = KotlinLogging.logger {}

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
    }
}
