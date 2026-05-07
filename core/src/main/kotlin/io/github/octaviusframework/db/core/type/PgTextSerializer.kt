package io.github.octaviusframework.db.core.type

import io.github.octaviusframework.db.api.exception.ConversionException
import io.github.octaviusframework.db.api.exception.ConversionExceptionMessage
import io.github.octaviusframework.db.api.exception.TypeRegistryException
import io.github.octaviusframework.db.api.exception.TypeRegistryExceptionMessage
import io.github.octaviusframework.db.api.toDataMap
import io.github.octaviusframework.db.api.type.DynamicDto
import io.github.octaviusframework.db.api.type.PgTyped
import io.github.octaviusframework.db.api.type.TypeHandler
import io.github.octaviusframework.db.core.config.DynamicDtoSerializationStrategy
import io.github.octaviusframework.db.core.type.registry.TypeRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.reflect.KClass

/**
 * Serializes Kotlin objects into PostgreSQL text protocol literals.
 * Handles escaping, quoting, and recursive structures (arrays and composites).
 */
internal class PgTextSerializer(
    private val typeRegistry: TypeRegistry,
    private val dynamicDtoStrategy: DynamicDtoSerializationStrategy
) {
    companion object {
        private val logger = KotlinLogging.logger {}
        private val NUMERIC_BOOLEAN_TYPES = setOf("bool", "int2", "int4", "int8", "float4", "float8", "numeric")
    }

    /**
     * Serializes a list into a PostgreSQL array literal (e.g., `{val1,val2}`).
     */
    fun serializeList(list: List<*>, skipDynamicDto: Boolean): String {
        if (list.isEmpty()) return "{}"
        return list.joinToString(prefix = "{", postfix = "}", separator = ",") { item ->
            if (item == null) "NULL" else {
                val literal = serializeValue(item, skipDynamicDto, useNullLiteral = true)
                if (shouldQuote(item)) escapeAndQuote(literal) else literal
            }
        }
    }

    /**
     * Serializes a data class into a PostgreSQL composite literal (e.g., `(val1,val2)`).
     */
    fun serializeComposite(obj: Any, skipDynamicDto: Boolean): String {
        val typeName = typeRegistry.getPgTypeNameForClass(obj::class)
        val oid = typeRegistry.getOidForName(typeName)
        val typeInfo = typeRegistry.getCompositeDefinition(oid)

        val valueMap = if (typeInfo.mapper != null) {
            logger.trace { "Using manual mapper for serialization of ${typeInfo.typeName}" }
            try {
                typeInfo.mapper.toDataMap(obj)
            } catch (e: Exception) {
                throw ConversionException(
                    ConversionExceptionMessage.COMPOSITE_MAPPER_FAILED,
                    targetType = typeInfo.typeName,
                    cause = e
                )
            }
        } else {
            obj.toDataMap()
        }

        return typeInfo.attributes.keys.joinToString(prefix = "(", postfix = ")", separator = ",") { key ->
            val value = valueMap[key]
            if (value == null) "" else {
                val literal = serializeValue(value, skipDynamicDto, useNullLiteral = false)
                if (shouldQuote(value)) escapeAndQuote(literal) else literal
            }
        }
    }

    private fun serializeValue(value: Any, skipDynamicDto: Boolean, useNullLiteral: Boolean): String {
        var current = value
        var wasPgTyped = false

        // Unpack @PgTyped wrappers
        while (current is PgTyped) {
            wasPgTyped = true
            current = current.value ?: return if (useNullLiteral) "NULL" else ""
        }

        // 1. Try standard handlers first
        typeRegistry.getHandlerByClass(current::class)?.let {
            @Suppress("UNCHECKED_CAST")
            return (it as TypeHandler<Any>).toPgString(current)
        }

        // 2. Try Dynamic DTO automatic conversion
        if (!wasPgTyped && !skipDynamicDto && current !is DynamicDto) {
            val kClass = current::class
            if (shouldUseDynamicDto(kClass)) {
                typeRegistry.getDynamicTypeNameForClass(kClass)?.let { typeName ->
                    current = DynamicDto.from(current, typeName, typeRegistry.getDynamicSerializer(typeName))
                }
            }
        }

        // 3. Handle recursive/complex types
        return when (current) {
            is Enum<*> -> {
                val typeName = typeRegistry.getPgTypeNameForClass(current::class)
                val oid = typeRegistry.getOidForName(typeName)
                typeRegistry.getEnumDefinition(oid).enumToValueMap[current] ?: current.name
            }
            is List<*> -> {
                serializeList(current, skipDynamicDto || wasPgTyped)
            }
            else -> {
                val kClass = current::class
                when {
                    kClass.isData -> serializeComposite(current, skipDynamicDto || wasPgTyped)
                    kClass.isValue -> throw TypeRegistryException(TypeRegistryExceptionMessage.KOTLIN_CLASS_NOT_MAPPED, kClass.qualifiedName ?: kClass.simpleName ?: "unknown", expectedCategory = "DYNAMIC")
                    else -> current.toString()
                }
            }
        }
    }

    private fun shouldQuote(item: Any): Boolean {
        var curr = item
        while (curr is PgTyped) curr = curr.value ?: return false

        typeRegistry.getHandlerByClass(curr::class)?.let { handler ->
            return handler.pgTypeName !in NUMERIC_BOOLEAN_TYPES
        }
        return true
    }

    private fun escapeAndQuote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(c)
            }
        }
        append('"')
    }

    private fun shouldUseDynamicDto(kClass: KClass<*>): Boolean {
        if (typeRegistry.getDynamicTypeNameForClass(kClass) == null) return false
        return when (dynamicDtoStrategy) {
            DynamicDtoSerializationStrategy.PREFER_DYNAMIC_DTO -> true
            DynamicDtoSerializationStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS -> !typeRegistry.isPgType(kClass)
            else -> false
        }
    }
}
