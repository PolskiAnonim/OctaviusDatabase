package org.octavius.database.type

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.*
import kotlinx.serialization.json.JsonElement
import org.octavius.data.OffsetTime
import org.octavius.data.exception.ConversionException
import org.octavius.data.exception.ConversionExceptionMessage
import org.octavius.data.exception.TypeRegistryException
import org.octavius.data.exception.TypeRegistryExceptionMessage
import org.octavius.data.toMap
import org.octavius.data.type.DynamicDto
import org.octavius.data.type.PgTyped
import org.octavius.data.util.clean
import org.octavius.database.config.DynamicDtoSerializationStrategy
import org.octavius.database.type.registry.TypeRegistry
import org.postgresql.util.PGobject
import java.time.ZoneOffset
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * Result of query expansion to JDBC positional format.
 * @param sql Query with '?' placeholders instead of named parameters.
 * @param params Ordered list of parameters for use in `PreparedStatement`.
 */
data class PositionalQuery(
    val sql: String,
    val params: List<Any?>
)

/**
 * Converts complex Kotlin types to appropriate SQL constructs for PostgreSQL.
 *
 * Enables using advanced types in queries without manual conversion.
 *
 * **Supported transformations:**
 * - `List<T>` → `ARRAY[...]`
 * - `data class` → `ROW(...)::type_name` (if registered as `@PgComposite`) or `dynamic_dto(...)` (if `@DynamicallyMappable`)
 * - `Enum` → `PGobject` (if registered as `@PgEnum`) or `dynamic_dto(...)` (if `@DynamicallyMappable`)
 * - `value class` → `dynamic_dto(...)` (must have `@DynamicallyMappable`, otherwise exception)
 * - `JsonElement` → `JSONB`
 * - `PgTyped<T>` → wraps value and adds explicit `::type_name` cast (highest priority)
 * - Date/time types → `java.sql.*` equivalents
 * - `Duration` → PostgreSQL `interval`
 *
 * **Dynamic DTO Strategy:**
 * Controls automatic conversion to `dynamic_dto` for `@DynamicallyMappable` types:
 * - `EXPLICIT_ONLY`: Only explicit `DynamicDto` wrappers are serialized as `dynamic_dto`
 * - `AUTOMATIC_WHEN_UNAMBIGUOUS` (default): Automatically converts to `dynamic_dto` if type is NOT registered
 *   as a formal PostgreSQL type (`@PgComposite`/`@PgEnum`). Avoids conflicts between formal and dynamic types.
 *
 */
internal class KotlinToPostgresConverter(
    private val typeRegistry: TypeRegistry,
    private val dynamicDtoStrategy: DynamicDtoSerializationStrategy = DynamicDtoSerializationStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @OptIn(ExperimentalTime::class)
    private val KOTLIN_TO_JDBC_CONVERTERS: Map<KClass<*>, (Any) -> Any> = mapOf(
        LocalDate::class to { v -> java.sql.Date.valueOf((v as LocalDate).toJavaLocalDate()) },
        LocalDateTime::class to { v -> java.sql.Timestamp.valueOf((v as LocalDateTime).toJavaLocalDateTime()) },
        LocalTime::class to { v -> java.sql.Time.valueOf((v as LocalTime).toJavaLocalTime()) },
        Instant::class to { v -> java.sql.Timestamp.from((v as Instant).toJavaInstant()) },
        OffsetTime::class to { v ->
            val ktTime = v as OffsetTime
            val javaOffset = ZoneOffset.ofTotalSeconds(ktTime.offset.totalSeconds)
            val javaTime = ktTime.time.toJavaLocalTime()
            java.time.OffsetTime.of(javaTime, javaOffset)
        },
        Duration::class to { v ->
            PGobject().apply {
                type = "interval"
                value = (v as Duration).toIsoString()
            }
        }
    )


    /**
     * Processes SQL query, expanding complex parameters into PostgreSQL constructs.
     *
     * Handles types as described in class KDoc
     *
     * @param sql Query with named parameters (e.g., `:param`).
     * @param params Parameter map for expansion, may contain complex Kotlin types.
     * @return `PositionalQuery` with processed SQL and flattened parameters.
     */
    fun expandParametersInQuery(sql: String, params: Map<String, Any?>): PositionalQuery {
        logger.debug { "Expanding parameters to positional query. Original params count: ${params.size}" }
        logger.trace { "Original SQL: $sql" }

        val parsedParameters = PostgresNamedParameterParser.parse(sql)

        if (parsedParameters.isEmpty()) {
            logger.debug { "No named parameters found, returning original query." }
            // Return empty parameter list since none were used
            return PositionalQuery(sql, emptyList())
        }

        val expandedSqlBuilder = StringBuilder(sql.length)
        val expandedParamsList = mutableListOf<Any?>()
        var lastIndex = 0

        parsedParameters.forEach { parsedParam ->
            val paramName = parsedParam.name

            if (!params.containsKey(paramName)) {
                throw IllegalArgumentException("Missing value for required SQL parameter: $paramName")
            }

            val paramValue = params[paramName]

            val (newPlaceholder, newParams) = expandParameter(paramValue)

            expandedSqlBuilder.append(sql, lastIndex, parsedParam.startIndex)
            expandedSqlBuilder.append(newPlaceholder)

            expandedParamsList.addAll(newParams)

            lastIndex = parsedParam.endIndex
        }

        expandedSqlBuilder.append(sql, lastIndex, sql.length)

        logger.debug { "Parameter expansion completed. Positional params count: ${expandedParamsList.size}" }
        logger.trace { "Expanded SQL: $expandedSqlBuilder" }

        return PositionalQuery(expandedSqlBuilder.toString(), expandedParamsList)
    }

    /**
     * Expands a single parameter into the appropriate SQL construct.
     * @param paramValue Parameter value to convert.
     * @param appendTypeCast Whether to append type cast (e.g., `::type_name`).
     * @return Pair: SQL placeholder (with `?`) and list of flattened parameters.
     */
    private fun expandParameter(
        paramValue: Any?,
        appendTypeCast: Boolean = true
    ): Pair<String, List<Any?>> {
        if (paramValue == null) {
            return "?" to listOf(null)
        }

        if (paramValue is PgTyped) {
            val (innerPlaceholder, innerParams) = expandParameter(
                paramValue.value,
                appendTypeCast = false
            )
            val finalPlaceholder = innerPlaceholder + "::" + paramValue.pgType
            return finalPlaceholder to innerParams
        }

        KOTLIN_TO_JDBC_CONVERTERS[paramValue::class]?.let { converter ->
            return "?" to listOf(converter(paramValue))
        }

        return when {
            paramValue is JsonElement -> {
                val pgObject = PGobject().apply {
                    type = "jsonb"
                    value = paramValue.toString()
                }
                "?" to listOf(pgObject)
            }
            isDataClass(paramValue) -> {
                if (appendTypeCast) {
                    tryExpandAsDynamicDto(paramValue) ?: expandRowParameter(paramValue, true)
                } else {
                    expandRowParameter(paramValue, false)
                }
            }
            paramValue is Array<*> -> validateTypedArrayParameter(paramValue)
            paramValue is List<*> -> expandArrayParameter(paramValue)
            paramValue is Enum<*> -> {
                if (appendTypeCast) {
                    tryExpandAsDynamicDto(paramValue) ?: createEnumParameter(paramValue)
                } else {
                    createEnumParameter(paramValue)
                }
            }
            isValueClass(paramValue) -> tryExpandAsDynamicDto(paramValue)
                ?: throw TypeRegistryException(
                    messageEnum = TypeRegistryExceptionMessage.KOTLIN_CLASS_NOT_MAPPED,
                    typeName = paramValue::class.qualifiedName,
                    cause = IllegalStateException("Value class must be annotated with @DynamicallyMappable.")
                )
            paramValue is String -> "?" to listOf(paramValue.clean())
            else -> "?" to listOf(paramValue)
        }
    }

    /**
     * Attempts to convert the given value to a `DynamicDto` wrapper, if allowed
     * by configuration and if the class is annotated with @DynamicallyMappable.
     *
     * **Strategy behavior:**
     * - `EXPLICIT_ONLY`: Always returns null (only explicit DynamicDto wrappers allowed)
     * - `AUTOMATIC_WHEN_UNAMBIGUOUS`: Returns null if type is already registered as formal PostgreSQL type
     *   (`@PgComposite`/`@PgEnum`), otherwise attempts dynamic conversion
     *
     * @return Expansion result as `Pair` or `null` if conversion is not possible/allowed.
     */
    private fun tryExpandAsDynamicDto(
        paramValue: Any
    ): Pair<String, List<Any?>>? {
        if (dynamicDtoStrategy == DynamicDtoSerializationStrategy.EXPLICIT_ONLY) return null
        if (dynamicDtoStrategy == DynamicDtoSerializationStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS && typeRegistry.isPgType(paramValue::class)) return null

        val dynamicTypeName = typeRegistry.getDynamicTypeNameForClass(paramValue::class) ?: return null
        val serializer = typeRegistry.getDynamicSerializer(dynamicTypeName)
        logger.trace { "Dynamically converting ${paramValue::class.simpleName} to dynamic_dto '$dynamicTypeName'" }

        val dynamicDtoWrapper = try {
            DynamicDto.from(paramValue, dynamicTypeName, serializer)
        } catch (ex: Exception) {
            logger.error(ex) { ex }; throw ex
        }
        return expandParameter(dynamicDtoWrapper)
    }

    /**
     * Validates a typed array (`Array<*>`) intended for direct JDBC passing.
     * Throws exception if element type is not a simple type.
     */
    private fun validateTypedArrayParameter(arrayValue: Array<*>): Pair<String, List<Any?>> {
        val componentType = arrayValue::class.java.componentType!!.kotlin
        if (isComplexComponentType(componentType)) {
            throw ConversionException(
                ConversionExceptionMessage.UNSUPPORTED_COMPONENT_TYPE_IN_ARRAY,
                arrayValue,
                targetType = componentType.qualifiedName ?: "unknown"
            )
        }
        return "?" to listOf(arrayValue)
    }

    /**
     * Checks whether KClass represents a complex type (e.g., data class)
     * that cannot be used in a typed JDBC array.
     */
    private fun isComplexComponentType(kClass: KClass<*>): Boolean {
        return kClass.isData || kClass == Map::class || kClass == List::class
    }

    /** Creates parameter for enum, mapping naming case for type and value. */
    private fun createEnumParameter(enumValue: Enum<*>): Pair<String, List<Any?>> {
        val dbTypeName = typeRegistry.getPgTypeNameForClass(enumValue::class)
        val typeInfo = typeRegistry.getEnumDefinition(dbTypeName)
        val finalDbValue = typeInfo.enumToValueMap[enumValue]
        val pgObject = PGobject().apply {
            value = finalDbValue; type = dbTypeName
        }
        return "?" to listOf(pgObject)
    }

    /**
     * Expands list into PostgreSQL ARRAY[...] construct.
     *
     * Recursively processes list elements, handling nested structures.
     * Empty list is converted to '{}' (empty PostgreSQL array).
     *
     * @param arrayValue List to convert.
     * @return Pair: ARRAY[...] placeholder and list of element parameters.
     */
    private fun expandArrayParameter(arrayValue: List<*>): Pair<String, List<Any?>> {
        if (arrayValue.isEmpty()) {
            return "'{}'" to emptyList()
        }

        val expandedParams = mutableListOf<Any?>()
        val placeholders = arrayValue.map { value ->
            val (placeholder, params) = expandParameter(value)
            expandedParams.addAll(params)
            placeholder
        }

        val arrayPlaceholder = "ARRAY[${placeholders.joinToString(", ")}]"
        return arrayPlaceholder to expandedParams
    }

    /**
     * Expands data class into PostgreSQL ROW(...)::type_name construct.
     *
     * Maps data class fields to composite type attributes in order
     * specified by TypeRegistry. Recursively processes nested fields.
     *
     * @param compositeValue Data class instance to convert.
     * @param appendTypeCast Whether to append type cast (e.g., `::type_name`).
     * @return Pair: ROW(...)::type_name placeholder and list of field parameters.
     * @throws TypeRegistryException if class is not registered.
     */
    private fun expandRowParameter(
        compositeValue: Any,
        appendTypeCast: Boolean = true
    ): Pair<String, List<Any?>> {
        val kClass = compositeValue::class
        val dbTypeName = typeRegistry.getPgTypeNameForClass(kClass)
        val typeInfo = typeRegistry.getCompositeDefinition(dbTypeName)
        val valueMap = compositeValue.toMap()
        val expandedParams = mutableListOf<Any?>()

        val placeholders = typeInfo.attributes.keys.map { dbAttributeName ->
            val value = valueMap[dbAttributeName]
            val (placeholder, params) = expandParameter(value)
            expandedParams.addAll(params)
            placeholder
        }

        val rowPlaceholder = if (appendTypeCast) {
            "ROW(${placeholders.joinToString(", ")})::$dbTypeName"
        } else {
            "ROW(${placeholders.joinToString(", ")})"
        }

        return rowPlaceholder to expandedParams
    }

    /**
     * Checks whether an object is an instance of a data class.
     */
    private fun isDataClass(obj: Any): Boolean {
        return obj::class.isData
    }

    /**
     * Checks whether an object is an instance of a value class.
     */
    private fun isValueClass(obj: Any): Boolean {
        return obj::class.isValue
    }
}
