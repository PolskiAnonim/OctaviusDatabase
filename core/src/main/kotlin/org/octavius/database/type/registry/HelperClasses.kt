package org.octavius.database.type.registry

import kotlin.reflect.KClass

// --- Data Models ---

/** Classification of PostgreSQL types for routing to appropriate converters. */
internal enum class TypeCategory {
    STANDARD, ENUM, COMPOSITE, ARRAY, DYNAMIC
}

/** Metadata for a PostgreSQL ENUM type, enabling bidirectional value mapping. */
internal data class PgEnumDefinition(
    val typeName: String,
    val valueToEnumMap: Map<String, Enum<*>>,
    val kClass: KClass<out Enum<*>>
) {
    val enumToValueMap: Map<Enum<*>, String> = valueToEnumMap.map { it.value to it.key }.toMap()
}

/** Metadata for a PostgreSQL COMPOSITE type with ordered attribute definitions. */
internal data class PgCompositeDefinition(
    val typeName: String,
    val attributes: Map<String, String>, // colName -> colType (ordered)
    val kClass: KClass<*>
)

/** Metadata for a PostgreSQL ARRAY type, linking to its element type. */
internal data class PgArrayDefinition(
    val typeName: String,
    val elementTypeName: String
)
