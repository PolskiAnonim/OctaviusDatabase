package org.octavius.database.type.registry

import kotlinx.serialization.KSerializer
import org.octavius.data.exception.TypeRegistryException
import org.octavius.data.exception.TypeRegistryExceptionMessage
import kotlin.reflect.KClass

/**
 * Central repository of PostgreSQL type metadata for bidirectional conversion.
 *
 * Provides lookup methods for:
 * - **Reading (DB → Kotlin)**: Category routing, enum/composite/array definitions
 * - **Writing (Kotlin → DB)**: Class to PostgreSQL type name mapping
 * - **Dynamic DTOs**: Serializer lookup for `@DynamicallyMappable` types
 *
 * Populated at startup by [TypeRegistryLoader] from classpath annotations and database schema.
 *
 * @see TypeRegistryLoader
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

    // Reverse maps for name-based lookup (if still needed, e.g. for dynamic_dto)
    private val pgNameToOidMap: Map<String, Int>
) {
    // --- READ Section (DB -> Kotlin) ---

    fun getCategory(oid: Int): TypeCategory {
        return oidCategoryMap[oid] ?: throw TypeRegistryException(
            TypeRegistryExceptionMessage.PG_TYPE_NOT_FOUND,
            typeName = "OID: $oid"
        )
    }

    fun getEnumDefinition(oid: Int): PgEnumDefinition {
        return enumsByOid[oid] ?: throw TypeRegistryException(
            TypeRegistryExceptionMessage.PG_TYPE_NOT_FOUND,
            typeName = "OID: $oid (expected ENUM)"
        )
    }

    fun getCompositeDefinition(oid: Int): PgCompositeDefinition {
        return compositesByOid[oid] ?: throw TypeRegistryException(
            TypeRegistryExceptionMessage.PG_TYPE_NOT_FOUND,
            typeName = "OID: $oid (expected COMPOSITE)"
        )
    }

    fun getArrayDefinition(oid: Int): PgArrayDefinition {
        return arraysByOid[oid] ?: throw TypeRegistryException(
            TypeRegistryExceptionMessage.PG_TYPE_NOT_FOUND,
            typeName = "OID: $oid (expected ARRAY)"
        )
    }

    /** Helper for getting OID by name (used during initialization or for specific types like dynamic_dto) */
    fun getOidForName(pgTypeName: String): Int {
        return pgNameToOidMap[pgTypeName] ?: throw TypeRegistryException(
            TypeRegistryExceptionMessage.PG_TYPE_NOT_FOUND,
            typeName = pgTypeName
        )
    }

    fun getProcedureDefinition(procedureName: String): PgProcedureDefinition {
        return procedures[procedureName] ?: throw TypeRegistryException(
            TypeRegistryExceptionMessage.PG_TYPE_NOT_FOUND,
            typeName = "$procedureName (expected PROCEDURE)"
        )
    }

    // For DYNAMIC type we need to find the serializer
    fun getDynamicSerializer(dynamicTypeName: String): KSerializer<Any> {
        return dynamicSerializers[dynamicTypeName] ?: throw TypeRegistryException(
            TypeRegistryExceptionMessage.DYNAMIC_TYPE_NOT_FOUND,
            typeName = dynamicTypeName
        )
    }

    // --- WRITE Section (Kotlin -> DB) ---

    fun getPgTypeNameForClass(clazz: KClass<*>): String {
        // Direct retrieval from map by class object
        return classToPgNameMap[clazz] ?: throw TypeRegistryException(
            TypeRegistryExceptionMessage.KOTLIN_CLASS_NOT_MAPPED,
            typeName = clazz.qualifiedName ?: clazz.simpleName ?: "unknown"
        )
    }

    fun getDynamicTypeNameForClass(clazz: KClass<*>): String? {
        return classToDynamicNameMap[clazz]
    }

    // Helper method for DynamicDTO

    fun isPgType(kClass: KClass<*>): Boolean {
        return classToPgNameMap.containsKey(kClass)
    }
}
