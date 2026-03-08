package org.octavius.database.type

import io.github.oshai.kotlinlogging.KotlinLogging
import org.octavius.data.exception.ConversionException
import org.octavius.data.exception.ConversionExceptionMessage
import org.octavius.data.exception.TypeRegistryException
import org.octavius.data.exception.TypeRegistryExceptionMessage
import org.octavius.data.toMap
import org.octavius.data.type.DYNAMIC_DTO
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
 * Result of a single parameter conversion.
 */
private data class ParameterConversion(val placeholder: String, val value: Any?)

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
        private val NUMERIC_BOOLEAN_TYPES = setOf("bool", "int2", "int4", "int8", "float4", "float8", "numeric")
    }

    private val serializer = PgTextSerializer()

    fun toPositionalQuery(sql: String, params: Map<String, Any?>): PositionalQuery {
        val parsedParameters = PostgresNamedParameterParser.parse(sql)
        if (parsedParameters.isEmpty()) return PositionalQuery(sql, emptyList())

        val finalParams = ArrayList<Any?>(parsedParameters.size)
        val transformedSql = buildString(sql.length + 256) {
            var lastIndex = 0
            for (parsedParam in parsedParameters) {
                val paramName = parsedParam.name
                require(params.containsKey(paramName)) { "Missing value for parameter: $paramName" }
                val paramValue = params[paramName]

                val conversion = convertParameter(paramValue, appendTypeCast = true)
                append(sql, lastIndex, parsedParam.startIndex).append(conversion.placeholder)
                finalParams.add(conversion.value)
                lastIndex = parsedParam.endIndex
            }
            append(sql, lastIndex, sql.length)
        }

        return PositionalQuery(transformedSql, finalParams)
    }

    private fun convertParameter(
        value: Any?,
        appendTypeCast: Boolean,
        skipDynamicDto: Boolean = false
    ): ParameterConversion {
        if (value == null) return ParameterConversion("?", null)

        val (unwrappedValue, pgType, updatedSkipDynamicDto) = unpackPgTyped(value, skipDynamicDto)
        if (unwrappedValue == null) {
            return ParameterConversion(if (appendTypeCast && pgType != null) "?::$pgType" else "?", null)
        }

        // 1. Try Dynamic DTO conversion
        if (!updatedSkipDynamicDto) {
            tryConvertAsDynamicDto(unwrappedValue, appendTypeCast)?.let { return it }
        }

        // 2. Delegate standard types to registry
        StandardTypeMappingRegistry.getHandlerByClass(unwrappedValue::class)?.let { handler ->
            val placeholder = if (appendTypeCast) "?::${pgType ?: handler.pgTypeName}" else "?"
            @Suppress("UNCHECKED_CAST")
            return ParameterConversion(placeholder, (handler as StandardTypeHandler<Any>).toJdbc(unwrappedValue))
        }

        // 3. Handle specialized types
        val resolvedType = pgType ?: if (appendTypeCast) resolveSqlType(unwrappedValue) else "text"
        val jdbcValue = when (unwrappedValue) {
            is Array<*> -> return handleArray(unwrappedValue)
            is List<*> -> handleList(unwrappedValue, pgType, updatedSkipDynamicDto)
            is Enum<*> -> handleEnum(unwrappedValue, pgType)
            else -> {
                when {
                    value::class.isData -> pgObject("text", serializer.serializeComposite(value, skipDynamicDto, pgType))
                    value::class.isValue -> throw TypeRegistryException(
                        TypeRegistryExceptionMessage.KOTLIN_CLASS_NOT_MAPPED,
                        value::class.qualifiedName ?: value::class.simpleName ?: "unknown"
                    )
                    else -> value
                }
            }
        }

        return ParameterConversion(if (appendTypeCast) "?::$resolvedType" else "?", jdbcValue)
    }

    private fun unpackPgTyped(value: Any, initialSkip: Boolean): Triple<Any?, String?, Boolean> {
        var current = value
        var pgType: String? = null
        var skipDynamicDto = initialSkip

        while (current is PgTyped) {
            if (pgType == null) pgType = current.pgType
            val nextValue = current.value ?: return Triple(null, pgType, true)
            current = nextValue
            skipDynamicDto = true
        }
        return Triple(current, pgType, skipDynamicDto)
    }

    private fun tryConvertAsDynamicDto(value: Any, appendTypeCast: Boolean): ParameterConversion? {
        if (dynamicDtoStrategy == DynamicDtoSerializationStrategy.EXPLICIT_ONLY && value !is DynamicDto) return null
        if (dynamicDtoStrategy == DynamicDtoSerializationStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS && typeRegistry.isPgType(value::class)) return null

        val dynamicTypeName = typeRegistry.getDynamicTypeNameForClass(value::class) ?: return null
        val dtSerializer = typeRegistry.getDynamicSerializer(dynamicTypeName)

        val dynamicDto = DynamicDto.from(value, dynamicTypeName, dtSerializer)
        return convertParameter(dynamicDto, appendTypeCast)
    }

    private fun handleArray(array: Array<*>): ParameterConversion {
        val componentType = array::class.java.componentType!!.kotlin
        if (componentType.isData || componentType == Map::class || componentType == List::class) {
            throw ConversionException(ConversionExceptionMessage.UNSUPPORTED_COMPONENT_TYPE_IN_ARRAY, array, componentType.qualifiedName ?: componentType.simpleName ?: "unknown")
        }
        return ParameterConversion("?", array)
    }

    private fun handleList(list: List<*>, pgType: String?, skipDynamicDto: Boolean): PGobject {
        val elementPgType = pgType?.let { StandardTypeMappingRegistry.resolveBaseTypeName(it) }
        return pgObject("text", serializer.serializeList(list, skipDynamicDto, elementPgType))
    }

    private fun handleEnum(enum: Enum<*>, pgType: String?): PGobject {
        val typeName = pgType ?: typeRegistry.getPgTypeNameForClass(enum::class)
        val typeInfo = typeRegistry.getEnumDefinition(typeName)
        return pgObject(typeName, typeInfo.enumToValueMap[enum] ?: enum.name)
    }

    private fun pgObject(type: String, value: String?) = PGobject().apply {
        this.type = type
        this.value = value
    }

    private fun resolveSqlType(value: Any): String {
        return when (value) {
            is List<*> -> {
                val firstNonNull = value.firstOrNull { it != null }
                if (firstNonNull != null) "${resolveSqlType(firstNonNull)}[]" else "text[]"
            }
            else -> {
                val kClass = value::class
                when {
                    shouldUseDynamicDto(kClass) -> DYNAMIC_DTO
                    typeRegistry.isPgType(kClass) -> typeRegistry.getPgTypeNameForClass(kClass)
                    else -> StandardTypeMappingRegistry.getHandlerByClass(kClass)?.pgTypeName ?: "text"
                }
            }
        }
    }

    private fun shouldUseDynamicDto(kClass: KClass<*>): Boolean {
        if (typeRegistry.getDynamicTypeNameForClass(kClass) == null) return false
        return when (dynamicDtoStrategy) {
            DynamicDtoSerializationStrategy.PREFER_DYNAMIC_DTO -> true
            DynamicDtoSerializationStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS -> !typeRegistry.isPgType(kClass)
            else -> false
        }
    }

    /**
     * Serializes complex objects into PostgreSQL text protocol literals.
     */
    private inner class PgTextSerializer {
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
            
            val valueMap = if (typeInfo.mapper != null) {
                logger.trace { "Using manual mapper for serialization of ${typeInfo.typeName}" }
                try {
                    typeInfo.mapper.toMap(obj)
                } catch (e: Exception) {
                    throw ConversionException(
                        ConversionExceptionMessage.COMPOSITE_MAPPER_FAILED,
                        targetType = typeInfo.typeName,
                        cause = e
                    )
                }
            } else {
                obj.toMap()
            }

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

            // 1. Try registry first
            StandardTypeMappingRegistry.getHandlerByClass(current::class)?.let {
                @Suppress("UNCHECKED_CAST")
                return (it as StandardTypeHandler<Any>).toPgString(current)
            }

            // 2. Try Dynamic DTO
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
                    val typeName = explicitType ?: typeRegistry.getPgTypeNameForClass(current::class)
                    typeRegistry.getEnumDefinition(typeName).enumToValueMap[current] ?: current.name
                }
                is List<*> -> serializeList(current, skipDynamicDto || wasPgTyped, explicitType?.let { StandardTypeMappingRegistry.resolveBaseTypeName(it) })
                else -> {
                    val kClass = current::class
                    when {
                        kClass.isData -> serializeComposite(current, skipDynamicDto || wasPgTyped, explicitType)
                        kClass.isValue -> throw TypeRegistryException(TypeRegistryExceptionMessage.KOTLIN_CLASS_NOT_MAPPED, kClass.qualifiedName ?: kClass.simpleName ?: "unknown")
                        else -> current.toString()
                    }
                }
            }
        }

        private fun shouldQuote(item: Any): Boolean {
            var curr = item
            while (curr is PgTyped) curr = curr.value ?: return false
            
            StandardTypeMappingRegistry.getHandlerByClass(curr::class)?.let { handler ->
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
    }
}
