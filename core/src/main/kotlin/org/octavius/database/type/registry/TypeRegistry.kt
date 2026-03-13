package org.octavius.database.type.registry

import kotlinx.serialization.KSerializer
import org.octavius.data.exception.TypeRegistryException
import org.octavius.data.exception.TypeRegistryExceptionMessage
import kotlin.reflect.KClass

/**
 * Central repository of PostgreSQL type metadata for bidirectional conversion.
 *
 * Provides lookup methods for:
 * - **Reading (DB → Kotlin)**: Category routing, enum/composite/array definitions by OID.
 * - **Writing (Kotlin → DB)**: Class to PostgreSQL type name mapping and OID discovery.
 * - **Dynamic DTOs**: Serializer lookup for `@DynamicallyMappable` types.
 *
 * Populated at startup by [TypeRegistryLoader].
 */
internal class TypeRegistry(
    // Main router: OID -> Category
    private val oidCategoryMap: Map<Int, TypeCategory>,
    // Specialized detail maps by OID
    private val enumsByOid: Map<Int, PgEnumDefinition>,
    private val compositesByOid: Map<Int, PgCompositeDefinition>,
    private val arraysByOid: Map<Int, PgArrayDefinition>,

    private val procedures: Map<String, PgProcedureDefinition>,
    // Mappings for writing (Kotlin Class -> PgType)
    private val classToPgNameMap: Map<KClass<*>, String>,
    // Dynamic mappings (Dynamic Key -> Kotlin Class)
    private val dynamicSerializers: Map<String, KSerializer<Any>>,
    private val classToDynamicNameMap: Map<KClass<*>, String>,
    // Reverse maps for name-based lookup
    private val pgNameToOidMap: Map<String, Int>
) {
    // --- READING (DB -> Kotlin) ---

    fun getCategory(oid: Int): TypeCategory = 
        oidCategoryMap[oid] ?: throwNotFound(oid)

    fun getEnumDefinition(oid: Int): PgEnumDefinition = 
        enumsByOid[oid] ?: throwNotFound(oid, "ENUM")

    fun getCompositeDefinition(oid: Int): PgCompositeDefinition = 
        compositesByOid[oid] ?: throwNotFound(oid, "COMPOSITE")

    fun getArrayDefinition(oid: Int): PgArrayDefinition = 
        arraysByOid[oid] ?: throwNotFound(oid, "ARRAY")

    fun getDynamicSerializer(dynamicTypeName: String): KSerializer<Any> =
        dynamicSerializers[dynamicTypeName] ?: throw TypeRegistryException(
            TypeRegistryExceptionMessage.DYNAMIC_TYPE_NOT_FOUND,
            typeName = dynamicTypeName
        )

    // --- WRITING (Kotlin -> DB) ---

    fun getPgTypeNameForClass(clazz: KClass<*>): String =
        classToPgNameMap[clazz] ?: throw TypeRegistryException(
            TypeRegistryExceptionMessage.KOTLIN_CLASS_NOT_MAPPED,
            typeName = clazz.qualifiedName ?: clazz.simpleName ?: "unknown"
        )

    fun getDynamicTypeNameForClass(clazz: KClass<*>): String? = 
        classToDynamicNameMap[clazz]

    fun getOidForName(pgTypeName: String): Int =
        pgNameToOidMap[pgTypeName] ?: throw TypeRegistryException(
            TypeRegistryExceptionMessage.PG_TYPE_NOT_FOUND,
            typeName = pgTypeName
        )

    fun isPgType(kClass: KClass<*>): Boolean = 
        classToPgNameMap.containsKey(kClass)

    // --- PROCEDURES ---

    fun getProcedureDefinition(procedureName: String): PgProcedureDefinition =
        procedures[procedureName] ?: throw TypeRegistryException(
            TypeRegistryExceptionMessage.PG_TYPE_NOT_FOUND,
            typeName = "$procedureName (expected PROCEDURE)"
        )

    // --- HELPERS ---

    private fun throwNotFound(oid: Int, expected: String? = null): Nothing {
        val details = if (expected != null) "OID: $oid (expected $expected)" else "OID: $oid"
        throw TypeRegistryException(TypeRegistryExceptionMessage.PG_TYPE_NOT_FOUND, typeName = details)
    }
}
