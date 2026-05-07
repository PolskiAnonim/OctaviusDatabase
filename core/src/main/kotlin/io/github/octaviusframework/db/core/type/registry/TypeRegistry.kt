package io.github.octaviusframework.db.core.type.registry

import io.github.octaviusframework.db.api.exception.TypeRegistryException
import io.github.octaviusframework.db.api.exception.TypeRegistryExceptionMessage
import io.github.octaviusframework.db.api.type.QualifiedName
import io.github.octaviusframework.db.api.type.TypeHandler
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

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
    // Specialized handlers (mostly for standard types)
    private val handlersByOid: Map<Int, TypeHandler<*>>,
    private val handlersByClass: Map<KClass<*>, TypeHandler<*>>,
    // Mappings for writing (Kotlin Class -> PgType)
    private val classToPgNameMap: Map<KClass<*>, QualifiedName>,
    // Dynamic mappings (Dynamic Key -> Kotlin Class)
    private val dynamicSerializers: Map<String, KSerializer<Any>>,
    private val classToDynamicNameMap: Map<KClass<*>, String>,
    // Reverse maps for name-based lookup
    private val pgNameToOidMap: Map<QualifiedName, Int>,
    // Human-readable names for OIDs (for error reporting)
    private val oidToNameMap: Map<Int, String>
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
            typeName = dynamicTypeName,
            expectedCategory = "DYNAMIC"
        )

    fun getHandlerByOid(oid: Int): TypeHandler<*>? = handlersByOid[oid]

    fun getHandlerByClass(kClass: KClass<*>): TypeHandler<*>? {
        handlersByClass[kClass]?.let { return it }
        // Handle subclasses (especially for JsonElement)
        return handlersByClass.entries.find { kClass.isSubclassOf(it.key) }?.value
    }

    // --- WRITING (Kotlin -> DB) ---

    fun getPgTypeNameForClass(clazz: KClass<*>): QualifiedName =
        classToPgNameMap[clazz] ?: throw TypeRegistryException(
            TypeRegistryExceptionMessage.KOTLIN_CLASS_NOT_MAPPED,
            typeName = clazz.qualifiedName ?: clazz.simpleName ?: "unknown"
        )

    fun getDynamicTypeNameForClass(clazz: KClass<*>): String? = 
        classToDynamicNameMap[clazz]

    fun getOidForName(name: QualifiedName): Int =
        pgNameToOidMap[name] ?: throw TypeRegistryException(
            TypeRegistryExceptionMessage.PG_TYPE_NOT_FOUND,
            typeName = name.toString()
        )

    fun isPgType(kClass: KClass<*>): Boolean = 
        classToPgNameMap.containsKey(kClass)

    // --- HELPERS ---

    private fun throwNotFound(oid: Int, expected: String? = null): Nothing {
        val typeName = oidToNameMap[oid] ?: "unknown"
        throw TypeRegistryException(
            messageEnum = TypeRegistryExceptionMessage.PG_TYPE_NOT_FOUND,
            typeName = typeName,
            oid = oid,
            expectedCategory = expected
        )
    }
}
