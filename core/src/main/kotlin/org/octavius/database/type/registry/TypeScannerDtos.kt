package org.octavius.database.type.registry

import kotlinx.serialization.KSerializer
import org.octavius.data.util.CaseConvention
import kotlin.reflect.KClass

/**
 * Information about a Kotlin enum annotated with @PgEnum.
 */
internal data class KtEnumInfo(
    val kClass: KClass<*>,
    val pgName: String,
    val pgConvention: CaseConvention,
    val kotlinConvention: CaseConvention
)

/**
 * Information about a Kotlin data class annotated with @PgComposite.
 */
internal data class KtCompositeInfo(
    val kClass: KClass<*>,
    val pgName: String
)

/**
 * Result of classpath scanning for type annotations.
 */
internal data class ClasspathScanResult(
    val enums: List<KtEnumInfo>,
    val composites: List<KtCompositeInfo>,
    val dynamicSerializers: Map<String, KSerializer<Any>>,
    val dynamicReverseMap: Map<KClass<*>, String>
)

/**
 * Result of database scanning for PostgreSQL type definitions.
 */
internal data class DatabaseScanResult(
    /** Enum type name -> list of enum values */
    val enums: Map<String, List<String>>,
    /** Composite type name -> ordered map of (attribute name -> attribute type) */
    val composites: Map<String, Map<String, String>>
)