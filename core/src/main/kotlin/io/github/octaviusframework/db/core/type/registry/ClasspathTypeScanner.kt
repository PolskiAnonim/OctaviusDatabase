package io.github.octaviusframework.db.core.type.registry

import io.github.classgraph.AnnotationClassRef
import io.github.classgraph.AnnotationEnumValue
import io.github.classgraph.ClassGraph
import io.github.classgraph.ScanResult
import io.github.octaviusframework.db.api.annotation.DynamicallyMappable
import io.github.octaviusframework.db.api.annotation.PgComposite
import io.github.octaviusframework.db.api.annotation.PgCompositeMapper
import io.github.octaviusframework.db.api.annotation.PgEnum
import io.github.octaviusframework.db.api.exception.InitializationException
import io.github.octaviusframework.db.api.exception.InitializationExceptionMessage
import io.github.octaviusframework.db.api.type.TypeHandler
import io.github.octaviusframework.db.api.util.CaseConvention
import io.github.octaviusframework.db.api.util.toSnakeCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/**
 * Scans the classpath for type mapping annotations.
 *
 * Discovers classes annotated with:
 * - [@PgEnum][PgEnum] - Kotlin enums mapped to PostgreSQL ENUMs
 * - [@PgComposite][PgComposite] - Data classes mapped to PostgreSQL COMPOSITE types
 * - [@DynamicallyMappable][DynamicallyMappable] - Classes for polymorphic dynamic_dto storage
 */
internal class ClasspathTypeScanner(
    private val packagesToScan: List<String>
) {
    /**
     * Scans configured packages and returns discovered type information.
     */
    fun scan(): ClasspathScanResult {
        val enumInfos = mutableListOf<KtEnumInfo>()
        val compositeInfos = mutableListOf<KtCompositeInfo>()
        val dynamicSerializers = mutableMapOf<String, KSerializer<Any>>()
        val dynamicReverseMap = mutableMapOf<KClass<*>, String>()
        val customHandlers = mutableListOf<TypeHandler<*>>()

        // Tracks uniqueness of PostgreSQL type names (Enums + Composites share namespace)
        val seenPgNames = mutableSetOf<Pair<String, String>>()

        try {
            logger.debug { "Scanning packages for annotations: ${packagesToScan.joinToString()}" }
            ClassGraph()
                .enableAllInfo()
                .acceptPackages("io.github.octaviusframework.db.api.type", *packagesToScan.toTypedArray())
                .scan().use { result ->
                    processEnums(result, enumInfos, seenPgNames)
                    processComposites(result, compositeInfos, seenPgNames)
                    processDynamicTypes(result, dynamicSerializers, dynamicReverseMap)
                    processCustomHandlers(result, customHandlers)
                }
        } catch (e: InitializationException) {
            throw e
        } catch (e: Exception) {
            throw InitializationException(InitializationExceptionMessage.CLASSPATH_SCAN_FAILED, cause = e)
        }

        return ClasspathScanResult(enumInfos, compositeInfos, dynamicSerializers, dynamicReverseMap, customHandlers)
    }

    private fun processEnums(
        scanResult: ScanResult,
        target: MutableList<KtEnumInfo>,
        seenNames: MutableSet<Pair<String, String>>
    ) {
        scanResult.getClassesWithAnnotation(PgEnum::class.java).forEach { classInfo ->
            if (!classInfo.isEnum) {
                throw InitializationException(
                    InitializationExceptionMessage.INITIALIZATION_FAILED,
                    details = classInfo.name,
                    cause = IllegalStateException("@PgEnum not on enum")
                )
            }

            val annotation = classInfo.getAnnotationInfo(PgEnum::class.java)
            val name = (annotation.parameterValues.getValue("name") as String)
                .ifBlank { classInfo.simpleName.toSnakeCase() }
            val schema = (annotation.parameterValues.getValue("schema") as String)

            if (!seenNames.add(schema to name)) {
                throw InitializationException(
                    messageEnum = InitializationExceptionMessage.DUPLICATE_PG_TYPE_DEFINITION,
                    details = if (schema.isBlank()) name else "$schema.$name",
                    cause = IllegalStateException("Duplicate PostgreSQL type name detected: '$name' in schema '$schema'. Found on ${classInfo.name}")
                )
            }

            val pgConv = (annotation.parameterValues.getValue("pgConvention") as AnnotationEnumValue)
                .loadClassAndReturnEnumValue() as CaseConvention
            val ktConv = (annotation.parameterValues.getValue("kotlinConvention") as AnnotationEnumValue)
                .loadClassAndReturnEnumValue() as CaseConvention
            val kClass = classInfo.loadClass().kotlin

            target.add(KtEnumInfo(kClass, name, schema, pgConv, ktConv))
        }
    }

    private fun processComposites(
        scanResult: ScanResult,
        target: MutableList<KtCompositeInfo>,
        seenNames: MutableSet<Pair<String, String>>
    ) {
        scanResult.getClassesWithAnnotation(PgComposite::class.java).forEach { classInfo ->
            if (classInfo.isEnum) {
                throw InitializationException(
                    InitializationExceptionMessage.INITIALIZATION_FAILED,
                    details = classInfo.name,
                    cause = IllegalStateException("@PgComposite on enum")
                )
            }

            val annotation = classInfo.getAnnotationInfo(PgComposite::class.java)
            val name = (annotation.parameterValues.getValue("name") as String)
                .ifBlank { classInfo.simpleName.toSnakeCase() }
            val schema = (annotation.parameterValues.getValue("schema") as String)

            if (!seenNames.add(schema to name)) {
                throw InitializationException(
                    messageEnum = InitializationExceptionMessage.DUPLICATE_PG_TYPE_DEFINITION,
                    details = if (schema.isBlank()) name else "$schema.$name",
                    cause = IllegalStateException("Duplicate PostgreSQL type name detected: '$name' in schema '$schema'. Found on ${classInfo.name}")
                )
            }

            val mapperClassInfo = annotation.parameterValues.getValue("mapper") as AnnotationClassRef
            val mapperClass = if (mapperClassInfo.name != "io.github.octaviusframework.db.api.annotation.DefaultPgCompositeMapper") {
                @Suppress("UNCHECKED_CAST")
                mapperClassInfo.loadClass().kotlin as KClass<out PgCompositeMapper<*>>
            } else null

            val kClass = classInfo.loadClass().kotlin
            target.add(KtCompositeInfo(kClass, name, schema, mapperClass))
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
                throw InitializationException(
                    InitializationExceptionMessage.INITIALIZATION_FAILED,
                    details = classInfo.name,
                    cause = IllegalStateException("Missing @Serializable")
                )
            }

            val annotation = classInfo.getAnnotationInfo(DynamicallyMappable::class.java)
            val typeName = annotation.parameterValues.getValue("typeName") as String

            if (targetSerializers.containsKey(typeName)) {
                throw InitializationException(
                    messageEnum = InitializationExceptionMessage.DUPLICATE_DYNAMIC_TYPE_DEFINITION,
                    details = typeName,
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
                throw InitializationException(
                    InitializationExceptionMessage.INITIALIZATION_FAILED,
                    details = typeName,
                    cause = IllegalStateException(
                        "Failed to obtain serializer for ${kClass.qualifiedName}. Ensure it is a valid @Serializable class/enum.",
                        e
                    )
                )
            }
        }
    }

    private fun processCustomHandlers(
        scanResult: ScanResult,
        target: MutableList<TypeHandler<*>>
    ) {
        val typeHandlerClassName = TypeHandler::class.qualifiedName!!

        scanResult.getClassesImplementing(typeHandlerClassName).forEach { classInfo ->
            if (classInfo.isAbstract || classInfo.isInterface || classInfo.name.startsWith("io.github.octaviusframework.db.core.type.registry.StandardTypeHandler")) {
                return@forEach
            }

            val kClass = classInfo.loadClass().kotlin
            try {
                val instance = (kClass.objectInstance ?: kClass.java.getDeclaredConstructor().newInstance()) as TypeHandler<*>
                target.add(instance)
                logger.debug { "Discovered custom TypeHandler for PostgreSQL type '${instance.pgTypeName}': ${kClass.simpleName}" }
            } catch (e: Exception) {
                throw InitializationException(
                    InitializationExceptionMessage.INITIALIZATION_FAILED,
                    details = classInfo.name,
                    cause = IllegalStateException("Failed to instantiate custom TypeHandler ${kClass.qualifiedName}. Ensure it is an 'object' or has a public no-arg constructor.", e)
                )
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}