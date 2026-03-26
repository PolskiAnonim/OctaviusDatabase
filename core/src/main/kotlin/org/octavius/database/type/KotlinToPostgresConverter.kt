package org.octavius.database.type

import io.github.oshai.kotlinlogging.KotlinLogging
import org.octavius.data.exception.ConversionException
import org.octavius.data.exception.ConversionExceptionMessage
import org.octavius.data.exception.TypeRegistryException
import org.octavius.data.exception.TypeRegistryExceptionMessage
import org.octavius.data.exception.requireBuilder
import org.octavius.data.type.DynamicDto
import org.octavius.data.type.PgTyped
import org.octavius.data.type.QualifiedName
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
 * Delegates standard type handling to [StandardTypeMappingRegistry]
 * and complex serialization to [PgTextSerializer].
 */
internal class KotlinToPostgresConverter(
    private val typeRegistry: TypeRegistry,
    private val dynamicDtoStrategy: DynamicDtoSerializationStrategy = DynamicDtoSerializationStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private val serializer = PgTextSerializer(typeRegistry, dynamicDtoStrategy)

    /**
     * Entry point for query transformation. Parses named parameters and converts values.
     */
    fun toPositionalQuery(sql: String, params: Map<String, Any?>): PositionalQuery {
        val parsedParameters = PostgresSqlPreprocessor.parse(sql)
        if (parsedParameters.isEmpty()) {
            return PositionalQuery(PostgresSqlPreprocessor.escapeQuestionMarks(sql), emptyList())
        }

        val finalParams = ArrayList<Any?>(parsedParameters.size)
        val transformedSql = buildString(sql.length + 256) {
            var lastIndex = 0
            for (parsedParam in parsedParameters) {
                val paramName = parsedParam.name
                requireBuilder(params.containsKey(paramName)) { "Missing value for parameter: $paramName" }
                val paramValue = params[paramName]

                val conversion = convertParameter(paramValue, appendTypeCast = true)
                
                // Escape question marks in the literal SQL parts between parameters
                val partBefore = sql.substring(lastIndex, parsedParam.startIndex)
                append(PostgresSqlPreprocessor.escapeQuestionMarks(partBefore))
                
                append(conversion.placeholder)
                finalParams.add(conversion.value)
                lastIndex = parsedParam.endIndex
            }
            // Escape question marks in the remaining literal SQL part
            val partAfter = sql.substring(lastIndex, sql.length)
            append(PostgresSqlPreprocessor.escapeQuestionMarks(partAfter))
        }

        return PositionalQuery(transformedSql, finalParams)
    }

    // --- INTERNAL CONVERSION LOGIC ---

    private fun convertParameter(
        value: Any?,
        appendTypeCast: Boolean,
        skipDynamicDto: Boolean = false
    ): ParameterConversion {
        if (value == null) return ParameterConversion("?", null)

        val (unwrappedValue, pgType, updatedSkipDynamicDto) = unpackPgTyped(value, skipDynamicDto)
        if (unwrappedValue == null) {
            val castSuffix = if (appendTypeCast && pgType != null) "::${pgType.quote()}" else ""
            return ParameterConversion("?$castSuffix", null)
        }

        // 1. Try Dynamic DTO conversion
        if (!updatedSkipDynamicDto) {
            tryConvertAsDynamicDto(unwrappedValue, appendTypeCast)?.let { return it }
        }

        // 2. Delegate standard types to registry
        StandardTypeMappingRegistry.getHandlerByClass(unwrappedValue::class)?.let { handler ->
            val finalType = pgType ?: QualifiedName("", handler.pgTypeName)
            val castSuffix = if (appendTypeCast) "::${finalType.quote()}" else ""
            @Suppress("UNCHECKED_CAST")
            return ParameterConversion("?$castSuffix", (handler as StandardTypeHandler<Any>).toJdbc(unwrappedValue))
        }

        // 3. Handle specialized types
        val resolvedType: QualifiedName = pgType ?: if (appendTypeCast) resolveSqlType(unwrappedValue) else QualifiedName("", "text")
        val jdbcValue = when (unwrappedValue) {
            is Array<*> -> return handleArray(unwrappedValue)
            is List<*> -> handleList(unwrappedValue, pgType, updatedSkipDynamicDto)
            is Enum<*> -> handleEnum(unwrappedValue, pgType)
            else -> {
                when {
                    unwrappedValue::class.isData -> pgObject("text", serializer.serializeComposite(unwrappedValue, updatedSkipDynamicDto, pgType))
                    unwrappedValue::class.isValue -> throw TypeRegistryException(
                        TypeRegistryExceptionMessage.KOTLIN_CLASS_NOT_MAPPED,
                        unwrappedValue::class.qualifiedName ?: unwrappedValue::class.simpleName ?: "unknown"
                    )
                    else -> unwrappedValue
                }
            }
        }

        return ParameterConversion(if (appendTypeCast) "?::${resolvedType.quote()}" else "?", jdbcValue)
    }

    private fun unpackPgTyped(value: Any, initialSkip: Boolean): Triple<Any?, QualifiedName?, Boolean> {
        var current = value
        var pgType: QualifiedName? = null
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

    private fun handleList(list: List<*>, pgType: QualifiedName?, skipDynamicDto: Boolean): PGobject {
        val elementPgType = pgType?.let { 
            if (it.isArray) it.copy(isArray = false) 
            else if (it.name.startsWith("_")) it.copy(name = it.name.substring(1))
            else it
        }
        return pgObject("text", serializer.serializeList(list, skipDynamicDto, elementPgType))
    }

    private fun handleEnum(enum: Enum<*>, pgType: QualifiedName?): PGobject {
        val typeName = pgType ?: typeRegistry.getPgTypeNameForClass(enum::class)
        val oid = typeRegistry.getOidForName(typeName)
        val typeInfo = typeRegistry.getEnumDefinition(oid)
        // Set type to "text" because we append explicit cast in SQL
        return pgObject("text", typeInfo.enumToValueMap[enum] ?: enum.name)
    }

    private fun pgObject(type: String, value: String?) = PGobject().apply {
        this.type = type
        this.value = value
    }

    private fun resolveSqlType(value: Any): QualifiedName {
        return when (value) {
            is List<*> -> {
                val firstNonNull = value.firstOrNull { it != null }
                if (firstNonNull != null) resolveSqlType(firstNonNull).asArray() else QualifiedName("", "text", isArray = true)
            }
            else -> {
                val kClass = value::class
                when {
                    shouldUseDynamicDto(kClass) -> typeRegistry.getPgTypeNameForClass(DynamicDto::class)
                    typeRegistry.isPgType(kClass) -> typeRegistry.getPgTypeNameForClass(kClass)
                    else -> {
                        val standardName = StandardTypeMappingRegistry.getHandlerByClass(kClass)?.pgTypeName ?: "text"
                        QualifiedName("", standardName)
                    }
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
}
