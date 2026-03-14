package org.octavius.database.type.registry

import org.octavius.data.annotation.PgCompositeMapper
import kotlin.reflect.KClass

// --- Data Models ---

/** Classification of PostgreSQL types for routing to appropriate converters. */
internal enum class TypeCategory {
    STANDARD, ENUM, COMPOSITE, ARRAY, DYNAMIC
}

/** Metadata for a PostgreSQL ENUM type, enabling bidirectional value mapping. */
internal data class PgEnumDefinition(
    val oid: Int,
    val typeName: String,
    val valueToEnumMap: Map<String, Enum<*>>,
    val kClass: KClass<out Enum<*>>
) {
    val enumToValueMap: Map<Enum<*>, String> = valueToEnumMap.map { it.value to it.key }.toMap()
}

/** Metadata for a PostgreSQL COMPOSITE type with ordered attribute definitions. */
internal data class PgCompositeDefinition(
    val oid: Int,
    val typeName: String,
    val attributes: Map<String, Int>, // colName -> colOid (ordered)
    val kClass: KClass<*>,
    val mapper: PgCompositeMapper<Any>? = null
) {
    val dbAttributes = attributes.toList()
}

/** Metadata for a PostgreSQL ARRAY type, linking to its element type. */
internal data class PgArrayDefinition(
    val oid: Int,
    val typeName: String,
    val elementOid: Int
)
