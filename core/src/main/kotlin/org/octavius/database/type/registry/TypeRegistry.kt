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
    // Main router: PgTypeName -> Category
    private val categoryMap: Map<String, TypeCategory>,

    // Specialized detail maps
    private val enums: Map<String, PgEnumDefinition>,
    private val composites: Map<String, PgCompositeDefinition>,
    private val arrays: Map<String, PgArrayDefinition>,

    // Mappings for writing (Kotlin Class -> PgType)
    private val classToPgNameMap: Map<KClass<*>, String>,

    // Dynamic mappings (Dynamic Key -> Kotlin Class)
    private val dynamicSerializers: Map<String, KSerializer<Any>>,
    private val classToDynamicNameMap: Map<KClass<*>, String>
) {
    // --- READ Section (DB -> Kotlin) ---

    fun getCategory(pgTypeName: String): TypeCategory {
        return categoryMap[pgTypeName] ?: throw TypeRegistryException(
            TypeRegistryExceptionMessage.PG_TYPE_NOT_FOUND,
            typeName = pgTypeName
        )
    }

    fun getEnumDefinition(pgTypeName: String): PgEnumDefinition {
        return enums[pgTypeName] ?: throw TypeRegistryException(
            TypeRegistryExceptionMessage.PG_TYPE_NOT_FOUND,
            typeName = "$pgTypeName (expected ENUM)"
        )
    }

    fun getCompositeDefinition(pgTypeName: String): PgCompositeDefinition {
        return composites[pgTypeName] ?: throw TypeRegistryException(
            TypeRegistryExceptionMessage.PG_TYPE_NOT_FOUND,
            typeName = "$pgTypeName (expected COMPOSITE)"
        )
    }

    fun getArrayDefinition(pgTypeName: String): PgArrayDefinition {
        return arrays[pgTypeName] ?: throw TypeRegistryException(
            TypeRegistryExceptionMessage.PG_TYPE_NOT_FOUND,
            typeName = "$pgTypeName (expected ARRAY)"
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
