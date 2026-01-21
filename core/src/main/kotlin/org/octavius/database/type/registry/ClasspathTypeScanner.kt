package org.octavius.database.type.registry

import io.github.classgraph.AnnotationEnumValue
import io.github.classgraph.ClassGraph
import io.github.classgraph.ScanResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.octavius.data.annotation.DynamicallyMappable
import org.octavius.data.annotation.PgComposite
import org.octavius.data.annotation.PgEnum
import org.octavius.data.exception.TypeRegistryException
import org.octavius.data.exception.TypeRegistryExceptionMessage
import org.octavius.data.util.CaseConvention
import org.octavius.data.util.toSnakeCase
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

        // Tracks uniqueness of PostgreSQL type names (Enums + Composites share namespace)
        val seenPgNames = mutableSetOf<String>()

        try {
            logger.debug { "Scanning packages for annotations: ${packagesToScan.joinToString()}" }
            ClassGraph()
                .enableAllInfo()
                .acceptPackages("org.octavius.data.type", *packagesToScan.toTypedArray())
                .scan().use { result ->
                    processEnums(result, enumInfos, seenPgNames)
                    processComposites(result, compositeInfos, seenPgNames)
                    processDynamicTypes(result, dynamicSerializers, dynamicReverseMap)
                }
        } catch (e: TypeRegistryException) {
            throw e
        } catch (e: Exception) {
            throw TypeRegistryException(TypeRegistryExceptionMessage.CLASSPATH_SCAN_FAILED, cause = e)
        }

        return ClasspathScanResult(enumInfos, compositeInfos, dynamicSerializers, dynamicReverseMap)
    }

    private fun processEnums(
        scanResult: ScanResult,
        target: MutableList<KtEnumInfo>,
        seenNames: MutableSet<String>
    ) {
        scanResult.getClassesWithAnnotation(PgEnum::class.java).forEach { classInfo ->
            if (!classInfo.isEnum) {
                throw TypeRegistryException(
                    TypeRegistryExceptionMessage.INITIALIZATION_FAILED,
                    typeName = classInfo.name,
                    cause = IllegalStateException("@PgEnum not on enum")
                )
            }

            val annotation = classInfo.getAnnotationInfo(PgEnum::class.java)
            val name = (annotation.parameterValues.getValue("name") as String)
                .ifBlank { classInfo.simpleName.toSnakeCase() }

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

    private fun processComposites(
        scanResult: ScanResult,
        target: MutableList<KtCompositeInfo>,
        seenNames: MutableSet<String>
    ) {
        scanResult.getClassesWithAnnotation(PgComposite::class.java).forEach { classInfo ->
            if (classInfo.isEnum) {
                throw TypeRegistryException(
                    TypeRegistryExceptionMessage.INITIALIZATION_FAILED,
                    typeName = classInfo.name,
                    cause = IllegalStateException("@PgComposite on enum")
                )
            }

            val annotation = classInfo.getAnnotationInfo(PgComposite::class.java)
            val name = (annotation.parameterValues.getValue("name") as String)
                .ifBlank { classInfo.simpleName.toSnakeCase() }

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
                throw TypeRegistryException(
                    TypeRegistryExceptionMessage.INITIALIZATION_FAILED,
                    typeName = classInfo.name,
                    cause = IllegalStateException("Missing @Serializable")
                )
            }

            val annotation = classInfo.getAnnotationInfo(DynamicallyMappable::class.java)
            val typeName = annotation.parameterValues.getValue("typeName") as String

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
                throw TypeRegistryException(
                    TypeRegistryExceptionMessage.INITIALIZATION_FAILED,
                    typeName = typeName,
                    cause = IllegalStateException(
                        "Failed to obtain serializer for ${kClass.qualifiedName}. Ensure it is a valid @Serializable class/enum.",
                        e
                    )
                )
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}