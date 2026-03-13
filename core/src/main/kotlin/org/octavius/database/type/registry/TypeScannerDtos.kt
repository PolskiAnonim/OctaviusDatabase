package org.octavius.database.type.registry

import kotlinx.serialization.KSerializer
import org.octavius.data.annotation.PgCompositeMapper
import org.octavius.data.util.CaseConvention
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
    val dynamicReverseMap: Map<KClass<*>, String>
)

/**
 * Result of database scanning for PostgreSQL type definitions.
 */
internal data class DatabaseScanResult(
    /** Schema -> Enum type name -> (OID, ArrayOID, list of enum values) */
    val enums: Map<String, Map<String, Triple<Int, Int, List<String>>>>,
    /** Schema -> Composite type name -> (OID, ArrayOID, ordered map of (attribute name -> attribute OID)) */
    val composites: Map<String, Map<String, Triple<Int, Int, Map<String, Int>>>>,
    /** Procedure name -> ordered list of parameters with modes */
    val procedures: Map<String, List<PgProcedureParam>>,
    /** OID -> Human readable name (for all discovered types) */
    val allOidNames: Map<Int, String>
)