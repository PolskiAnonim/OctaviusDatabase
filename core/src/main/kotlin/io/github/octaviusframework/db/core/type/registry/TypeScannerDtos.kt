package io.github.octaviusframework.db.core.type.registry

import io.github.octaviusframework.db.api.annotation.PgCompositeMapper
import io.github.octaviusframework.db.api.type.TypeHandler
import io.github.octaviusframework.db.api.util.CaseConvention
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

/**
 * Information about a Kotlin enum annotated with @PgEnum.
 */
internal data class KtEnumInfo(
    val kClass: KClass<*>,
    val pgName: String,
    val schema: String,
    val pgConvention: CaseConvention,
    val kotlinConvention: CaseConvention
)

/**
 * Information about a Kotlin data class annotated with @PgComposite.
 */
internal data class KtCompositeInfo(
    val kClass: KClass<*>,
    val pgName: String,
    val schema: String,
    val mapperClass: KClass<out PgCompositeMapper<*>>? = null
)

/**
 * Result of classpath scanning for type annotations.
 */
internal data class ClasspathScanResult(
    val enums: List<KtEnumInfo>,
    val composites: List<KtCompositeInfo>,
    val dynamicSerializers: Map<String, KSerializer<Any>>,
    val dynamicReverseMap: Map<KClass<*>, String>,
    val customHandlers: List<TypeHandler<*>>
)

/**
 * Result of database scanning for PostgreSQL type definitions.
 */
internal data class DatabaseScanResult(
    /** Schema -> Enum type name -> (OID, ArrayOID, list of enum values) */
    val enums: Map<String, Map<String, Triple<Int, Int, List<String>>>>,
    /** Schema -> Composite type name -> (OID, ArrayOID, ordered map of (attribute name -> attribute OID)) */
    val composites: Map<String, Map<String, Triple<Int, Int, Map<String, Int>>>>,
    /** OID -> Human readable name (for all discovered types) */
    val allOidNames: Map<Int, String>
)