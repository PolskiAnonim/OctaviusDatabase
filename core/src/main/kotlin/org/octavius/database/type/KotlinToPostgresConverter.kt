package org.octavius.database.type

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonElement
import org.octavius.data.exception.ConversionException
import org.octavius.data.exception.ConversionExceptionMessage
import org.octavius.data.exception.TypeRegistryException
import org.octavius.data.exception.TypeRegistryExceptionMessage
import org.octavius.data.toMap
import org.octavius.data.type.DynamicDto
import org.octavius.data.type.PgTyped
import org.octavius.database.config.DynamicDtoSerializationStrategy
import org.octavius.database.type.registry.TypeRegistry
import org.postgresql.util.PGobject
import kotlin.reflect.KClass

/**
 * Result of parameter expansion: SQL with positional markers and the converted values.
 */
data class PositionalQuery(val sql: String, val params: List<Any?>)

/**
 * Orchestrates conversion of Kotlin objects to PostgreSQL JDBC parameters.
 * Delegates standard type handling to [StandardTypeMappingRegistry].
 */
internal class KotlinToPostgresConverter(
    private val typeRegistry: TypeRegistry,
    private val dynamicDtoStrategy: DynamicDtoSerializationStrategy = DynamicDtoSerializationStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private val serializer = PgTextSerializer(typeRegistry, dynamicDtoStrategy)

    fun expandParametersInQuery(sql: String, params: Map<String, Any?>): PositionalQuery {
        val parsedParameters = PostgresNamedParameterParser.parse(sql)
        if (parsedParameters.isEmpty()) return PositionalQuery(sql, emptyList())

        val sb = StringBuilder(sql.length + 256)
        val finalParams = ArrayList<Any?>(parsedParameters.size)
        var lastIndex = 0

        for (parsedParam in parsedParameters) {
            val paramName = parsedParam.name
            val paramValue = if (params.containsKey(paramName)) params[paramName] else 
                throw IllegalArgumentException("Missing value for parameter: $paramName")

            val (placeholder, value) = expandParameter(paramValue, appendTypeCast = true)
            sb.append(sql, lastIndex, parsedParam.startIndex).append(placeholder)
            finalParams.add(value)
            lastIndex = parsedParam.endIndex
        }
        return PositionalQuery(sb.append(sql, lastIndex, sql.length).toString(), finalParams)
    }

    private fun expandParameter(value: Any?, appendTypeCast: Boolean, skipDynamicDto: Boolean = false): Pair<String, Any?> {
        if (value == null) return "?" to null

        var current = value
        var outermostPgType: String? = null
        var currentSkipDynamicDto = skipDynamicDto

        // 1. Unpack explicit type wrapping
        while (current is PgTyped) {
            if (outermostPgType == null) outermostPgType = current.pgType
            current = current.value ?: return (if (appendTypeCast) "?::$outermostPgType" else "?") to null
            currentSkipDynamicDto = true
        }

        // 2. Try Dynamic DTO conversion (if not forced to composite/standard via PgTyped)
        if (!currentSkipDynamicDto) {
            tryExpandAsDynamicDto(current, appendTypeCast)?.let { return it }
        }

        // 3. Delegate standard types to registry (handles String, Number, Boolean, Instant, JsonElement, etc.)
        StandardTypeMappingRegistry.getHandlerByClass(current::class)?.let { handler ->
            val placeholder = if (appendTypeCast) "?::${outermostPgType ?: handler.pgTypeName}" else "?"
            return placeholder to handler.toJdbc(current)
        }

        // 4. Handle specialized types
        val resolvedType = outermostPgType ?: if (appendTypeCast) resolveSqlType(current) else "text"
        val pgValue = when (current) {
            is Array<*> -> return validateTypedArrayParameter(current) // Arrays are passed directly via JDBC
            is List<*> -> {
                val elementPgType = outermostPgType?.let { StandardTypeMappingRegistry.resolveBaseTypeName(it) }
                PGobject().apply { type = "text"; this.value = serializer.serializeList(current, currentSkipDynamicDto, elementPgType) }
            }
            is Enum<*> -> {
                val typeName = outermostPgType ?: typeRegistry.getPgTypeNameForClass(current::class)
                val typeInfo = typeRegistry.getEnumDefinition(typeName)
                PGobject().apply { type = typeName; this.value = typeInfo.enumToValueMap[current] ?: current.name }
            }
            else -> {
                if (current::class.isData) {
                    PGobject().apply { type = "text"; this.value = serializer.serializeComposite(current, currentSkipDynamicDto, outermostPgType) }
                } else if (current::class.isValue) {
                    throw TypeRegistryException(TypeRegistryExceptionMessage.KOTLIN_CLASS_NOT_MAPPED, current::class.qualifiedName)
                } else {
                    current
                }
            }
        }

        return (if (appendTypeCast) "?::$resolvedType" else "?") to pgValue
    }

    private fun tryExpandAsDynamicDto(paramValue: Any, appendTypeCast: Boolean): Pair<String, Any?>? {
        if (dynamicDtoStrategy == DynamicDtoSerializationStrategy.EXPLICIT_ONLY && paramValue !is DynamicDto) return null
        if (dynamicDtoStrategy == DynamicDtoSerializationStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS && typeRegistry.isPgType(paramValue::class)) return null

        val dynamicTypeName = typeRegistry.getDynamicTypeNameForClass(paramValue::class) ?: return null
        val dtSerializer = typeRegistry.getDynamicSerializer(dynamicTypeName)

        val dynamicDtoWrapper = DynamicDto.from(paramValue, dynamicTypeName, dtSerializer)
        return expandParameter(dynamicDtoWrapper, appendTypeCast)
    }

    private fun validateTypedArrayParameter(arrayValue: Array<*>): Pair<String, Any?> {
        val componentType = arrayValue::class.java.componentType!!.kotlin
        if (componentType.isData || componentType == Map::class || componentType == List::class) {
            throw ConversionException(ConversionExceptionMessage.UNSUPPORTED_COMPONENT_TYPE_IN_ARRAY, arrayValue, componentType.qualifiedName)
        }
        return "?" to arrayValue
    }

    private fun resolveSqlType(value: Any): String {
        if (value is PgTyped) return value.pgType
        return when (value) {
            is List<*> -> "${value.firstOrNull { it != null }?.let { resolveSqlType(it) } ?: "text"}[]"
            is Enum<*> -> try { typeRegistry.getPgTypeNameForClass(value::class) } catch (e: Exception) { "text" }
            else -> {
                if (dynamicDtoStrategy != DynamicDtoSerializationStrategy.EXPLICIT_ONLY && !typeRegistry.isPgType(value::class)) {
                    typeRegistry.getDynamicTypeNameForClass(value::class)?.let { return it }
                }
                try { if (typeRegistry.isPgType(value::class)) return typeRegistry.getPgTypeNameForClass(value::class) } catch (e: Exception) {}
                StandardTypeMappingRegistry.getHandlerByClass(value::class)?.pgTypeName ?: "text"
            }
        }
    }

    /**
     * Serializes complex objects into PostgreSQL text protocol literals.
     */
    private class PgTextSerializer(
        private val typeRegistry: TypeRegistry,
        private val dynamicDtoStrategy: DynamicDtoSerializationStrategy
    ) {
        fun serializeList(list: List<*>, skipDynamicDto: Boolean, explicitElementType: String?): String {
            if (list.isEmpty()) return "{}"
            return list.joinToString(prefix = "{", postfix = "}", separator = ",") { item ->
                if (item == null) "NULL" else {
                    val literal = serializeValue(item, skipDynamicDto, explicitElementType)
                    if (shouldQuote(item)) escapeAndQuote(literal) else literal
                }
            }
        }

        fun serializeComposite(obj: Any, skipDynamicDto: Boolean, explicitType: String?): String {
            val typeName = explicitType ?: typeRegistry.getPgTypeNameForClass(obj::class)
            val typeInfo = typeRegistry.getCompositeDefinition(typeName)
            val valueMap = obj.toMap()

            return typeInfo.attributes.keys.joinToString(prefix = "(", postfix = ")", separator = ",") { key ->
                val value = valueMap[key]
                if (value == null) "" else {
                    val literal = serializeValue(value, skipDynamicDto, null)
                    if (shouldQuote(value)) escapeAndQuote(literal) else literal
                }
            }
        }

        private fun serializeValue(value: Any, skipDynamicDto: Boolean, explicitType: String?): String {
            var current = value
            var wasPgTyped = false

            while (current is PgTyped) {
                wasPgTyped = true
                current = current.value ?: return "NULL"
            }

            // 1. Try registry first (handles built-ins including String, Number, Boolean, JSON)
            StandardTypeMappingRegistry.getHandlerByClass(current::class)?.let { return it.toPgString(current) }

            // 2. Try Dynamic DTO
            if (!wasPgTyped && !skipDynamicDto && current !is Enum<*> && current !is JsonElement) {
                if (dynamicDtoStrategy != DynamicDtoSerializationStrategy.EXPLICIT_ONLY && !typeRegistry.isPgType(current::class)) {
                    typeRegistry.getDynamicTypeNameForClass(current::class)?.let { typeName ->
                        val dto = DynamicDto.from(current, typeName, typeRegistry.getDynamicSerializer(typeName))
                        current = if (dto is PgTyped) dto.value ?: "NULL" else dto.toString()
                    }
                }
            }

            // 3. Handle recursive/complex types
            return when (current) {
                is Enum<*> -> {
                    val typeName = explicitType ?: typeRegistry.getPgTypeNameForClass(current::class)
                    typeRegistry.getEnumDefinition(typeName).enumToValueMap[current] ?: current.name
                }
                is List<*> -> serializeList(current, skipDynamicDto || wasPgTyped, explicitType?.let { StandardTypeMappingRegistry.resolveBaseTypeName(it) })
                else -> {
                    if (current::class.isData) serializeComposite(current, skipDynamicDto || wasPgTyped, explicitType)
                    else if (current::class.isValue) throw TypeRegistryException(TypeRegistryExceptionMessage.KOTLIN_CLASS_NOT_MAPPED, current::class.qualifiedName)
                    else current.toString()
                }
            }
        }

        private fun shouldQuote(item: Any): Boolean {
            var curr = item
            while (curr is PgTyped) curr = curr.value ?: return false
            // Registry types know if they are numeric/boolean
            StandardTypeMappingRegistry.getHandlerByClass(curr::class)?.let { handler ->
                return handler.pgTypeName !in setOf("bool", "int2", "int4", "int8", "float4", "float8", "numeric")
            }
            return true
        }

        private fun escapeAndQuote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
