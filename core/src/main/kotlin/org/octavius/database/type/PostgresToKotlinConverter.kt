package org.octavius.database.type

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import org.octavius.data.exception.ConversionException
import org.octavius.data.exception.ConversionExceptionMessage
import org.octavius.data.exception.TypeRegistryException
import org.octavius.data.exception.TypeRegistryExceptionMessage
import org.octavius.data.toDataObject
import org.octavius.database.type.registry.*

/**
 * Converts values from PostgreSQL (as `String`) to appropriate Kotlin types.
 *
 * Supports standard types, enums, composites, and arrays, using metadata
 * from `TypeRegistry` for dynamic mapping.
 *
 * @param typeRegistry Registry containing metadata about PostgreSQL types.
 */
internal class PostgresToKotlinConverter(private val typeRegistry: TypeRegistry) {
    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * Minimum number of array elements required to trigger parallel processing.
         * Only applies to COMPOSITE and DYNAMIC types where per-element processing is expensive.
         * For simple types (int, text, etc.) sequential processing is always faster.
         */
        private const val PARALLEL_THRESHOLD = 20
    }

    /**
     * Main conversion function that delegates to specialized handlers.
     *
     * Supports all type categories: STANDARD, ENUM, ARRAY, COMPOSITE, and DYNAMIC.
     *
     * @param value Value from database as `String` (can be `null`).
     * @param pgTypeName Type name in PostgreSQL (e.g., "int4", "my_enum", "dynamic_dto").
     * @return Converted value or `null` if `value` was `null`.
     * @throws org.octavius.data.exception.TypeRegistryException if type is unknown.
     * @throws ConversionException if conversion fails.
     */
    fun convert(value: String?, pgTypeName: String): Any? {
        if (value == null) {
            logger.trace { "Converting null value for type: $pgTypeName" }
            return null
        }

        logger.trace { "Converting value '$value' from PostgreSQL type: $pgTypeName" }
        val category = typeRegistry.getCategory(pgTypeName)

        return when (category) {
            TypeCategory.STANDARD -> {
                logger.trace { "Converting standard value '$value' for type $pgTypeName" }
                convertStandardType(value, pgTypeName)
            }

            TypeCategory.ENUM -> {
                logger.trace { "Converting enum value '$value' for type $pgTypeName" }
                val def = typeRegistry.getEnumDefinition(pgTypeName)
                convertEnum(value, def)
            }

            TypeCategory.ARRAY -> {
                logger.trace { "Converting array value for type $pgTypeName" }
                val def = typeRegistry.getArrayDefinition(pgTypeName)
                convertArray(value, def)
            }

            TypeCategory.COMPOSITE -> {
                logger.trace { "Converting composite value for type $pgTypeName" }
                val def = typeRegistry.getCompositeDefinition(pgTypeName)
                convertCompositeType(value, def)
            }

            TypeCategory.DYNAMIC -> {
                logger.trace { "Converting dynamic DTO value for type $pgTypeName" }
                convertDynamicType(value)
            }
        }
    }

    /**
     * Deserializes the special `dynamic_dto` type to an appropriate Kotlin class.
     *
     * @param value Raw value from database in composite format `("typeName", "jsonData")`.
     * @return Instance of appropriate `data class` with `@DynamicallyMappable` annotation.
     */
    private fun convertDynamicType(value: String): Any {
        // null handled in convert method
        // json itself cannot be null in composite - this is also consistent with the write where value cannot be null

        val parts: List<String?> = parseNestedStructure(value)

        if (parts.size != 2) {
            throw TypeRegistryException(TypeRegistryExceptionMessage.WRONG_FIELD_NUMBER_IN_COMPOSITE, typeName = "dynamic_dto")
        }

        val typeName = parts[0]
        val jsonDataString = parts[1]

        if (typeName == null || jsonDataString == null) {
            throw ConversionException(ConversionExceptionMessage.INVALID_DYNAMIC_DTO_FORMAT, value = value)
        }

        // Use TypeRegistry to safely find the serializer
        val serializer = typeRegistry.getDynamicSerializer(typeName)

        return try {
            Json.decodeFromString(serializer, jsonDataString)
        } catch (e: Exception) {
            throw ConversionException(ConversionExceptionMessage.JSON_DESERIALIZATION_FAILED, targetType = typeName, rowData = mapOf("json" to jsonDataString), cause = e)
        }
    }

    /**
     * Converts standard PostgreSQL types to appropriate Kotlin types.
     *
     * Delegates to `StandardTypeMappingRegistry`, which is the single source of truth.
     *
     * @param value Value from database as String.
     * @param pgTypeName Name of standard PostgreSQL type.
     * @return Converted value.
     * @throws ConversionException if conversion fails.
     */
    private fun convertStandardType(value: String, pgTypeName: String): Any { // null handled in convert method
        // 1. Find the appropriate handler in the central registry
        val handler = StandardTypeMappingRegistry.getHandler(pgTypeName)

        if (handler == null) {
            logger.warn { "No standard type handler found for PostgreSQL type '$pgTypeName'. Returning raw string value." }
            return value // Default behavior: return string if type is unknown
        }

        // 2. Use the 'fromString' function from the handler for conversion
        return try {
            handler.fromString(value)
        } catch (e: Exception) {
            throw ConversionException(
                messageEnum = ConversionExceptionMessage.VALUE_CONVERSION_FAILED,
                value = value,
                targetType = handler.kotlinClass.simpleName ?: pgTypeName,
                cause = e
            )
        }
    }

    /**
     * Converts enum value from PostgreSQL to Kotlin enum.
     *
     * Maps value names according to conventions specified in TypeRegistry:
     *
     * @param value Enum value from database.
     * @param typeInfo Enum type information from TypeRegistry.
     * @return Kotlin enum instance.
     * @throws TypeRegistryException if enum class not found.
     * @throws ConversionException if conversion fails.
     */
    private fun convertEnum(value: String, typeInfo: PgEnumDefinition): Any { // null handled in convert method

        return typeInfo.valueToEnumMap[value]
            ?: throw ConversionException(
                messageEnum = ConversionExceptionMessage.ENUM_CONVERSION_FAILED,
                value = value,
                targetType = typeInfo.kClass.simpleName
            )
    }

    /**
     * Converts PostgreSQL array to `List<Any?>`.
     *
     * Supports nested arrays and recursively processes elements
     * according to element type specified in TypeRegistry.
     *
     * For COMPOSITE and DYNAMIC element types with more than [PARALLEL_THRESHOLD] elements,
     * processing is done in parallel using Java's parallel streams.
     * Simple types (int, text, etc.) are always processed sequentially as parallelization
     * overhead outweighs the benefits.
     *
     * @param value String representing PostgreSQL array (format: {elem1,elem2,...}).
     * @param typeInfo Array type information from TypeRegistry.
     * @return List of converted elements.
     * @throws ConversionException if parsing fails.
     */
    private fun convertArray(value: String, typeInfo: PgArrayDefinition): List<Any?> {

        logger.trace { "Parsing PostgreSQL array with element type: ${typeInfo.elementTypeName}" }

        val elements: List<String?> = parseNestedStructure(value)

        logger.trace { "Parsed ${elements.size} array elements" }

        val convertElement: (String?) -> Any? = { elementValue ->
            // Check if the string representing the element ITSELF is an array.
            val isNestedArray = elementValue?.startsWith('{') ?: false

            // If it's a nested array, recursively invoke conversion
            // for the ENTIRE array type (e.g., "_text"), not its element ("text").
            // Otherwise, continue with standard elementType logic.
            val typeNameToUse = if (isNestedArray) typeInfo.typeName else typeInfo.elementTypeName

            convert(elementValue, typeNameToUse)
        }

        // Use parallel processing for large arrays, sequential for small ones
        val elementCategory = typeRegistry.getCategory(typeInfo.elementTypeName)
        return if ((elementCategory == TypeCategory.COMPOSITE || elementCategory == TypeCategory.DYNAMIC) && elements.size >= PARALLEL_THRESHOLD) {
            logger.trace { "Using parallel processing for ${elements.size} elements" }
            elements.parallelStream()
                .map(convertElement)
                .toList()
        } else {
            elements.map(convertElement)
        }
    }

    /**
     * Converts PostgreSQL composite type to Kotlin `data class`.
     * Uses cache for KClass and delegates to `toDataObject` (which has its own cache).
     */
    private fun convertCompositeType(value: String, typeInfo: PgCompositeDefinition): Any { // null handled in convert method

        logger.trace { "Converting composite type ${typeInfo.typeName} to class: ${typeInfo.kClass.qualifiedName}" }

        // 1. Parse string into list of raw values
        val fieldValues: List<String?> = parseNestedStructure(value)


        val dbAttributes = typeInfo.attributesList

        if (fieldValues.size != dbAttributes.size) {
            val ex = TypeRegistryException(TypeRegistryExceptionMessage.WRONG_FIELD_NUMBER_IN_COMPOSITE, typeName = typeInfo.typeName)
            logger.error(ex) { ex }
            throw ex
        }

        logger.trace { "Converting ${dbAttributes.size} composite fields" }
        val constructorArgsMap = dbAttributes.mapIndexed { index, entry ->
            val convertedValue = convert(fieldValues[index], entry.value)
            entry.key to convertedValue
        }.toMap()

        return try {
            val result = constructorArgsMap.toDataObject(typeInfo.kClass)
            logger.trace { "Successfully created instance of ${typeInfo.kClass.simpleName}" }
            result
        } catch (e: Exception) { // This should always be a ConversionException
            logger.error(e) { e }
            throw e
        }
    }

    // =================================================================
    // --- POSTGRESQL STRUCTURE PARSER ---
    // =================================================================

    private data class ParserState(
        var i: Int = 0,
        var inQuotes: Boolean = false,
        var nestingLevel: Int = 0,
        var currentElementStart: Int = 0
    )

    /**
     * Universal parser for nested structures (arrays and composites).
     * Handles quotes, escaping, `NULL` values, and nesting.
     */
    private fun parseNestedStructure(input: String): List<String?> {
        if (input.length <= 2) return emptyList()

        val contentView: CharSequence = input.subSequence(1, input.length - 1)

        val elements = mutableListOf<String?>()
        val state = ParserState()

        while (state.i < contentView.length) {
            val char = contentView[state.i]

            if (state.inQuotes) {
                processInQuotes(contentView, state)
            } else {
                processOutsideQuotes(char, contentView, state, elements)
            }
            state.i++
        }

        elements.add(unescapeValue(contentView, state.currentElementStart, contentView.length))
        return elements
    }

    private fun processInQuotes(contentView: CharSequence, state: ParserState) {
        val char = contentView[state.i]
        when (char) {
            '\\' -> state.i++ // Skip next character
            // When it's a quote escaping another (i.e., "")
            // on the next loop iteration the parser will simply change state back
            // Additional handling is pointless since splitting is done by comma
            '"' -> state.inQuotes = false
        }
    }

    private fun processOutsideQuotes(
        char: Char,
        contentView: CharSequence,
        state: ParserState,
        elements: MutableList<String?>
    ) {
        when (char) {
            '"' -> state.inQuotes = true
            '{', '(' -> state.nestingLevel++
            '}', ')' -> state.nestingLevel--
            ',' -> {
                if (state.nestingLevel == 0) {
                    elements.add(unescapeValue(contentView, state.currentElementStart, state.i))
                    state.currentElementStart = state.i + 1
                }
            }
        }
    }

    private fun unescapeValue(source: CharSequence, start: Int, end: Int): String? {
        return if (start >= end) {
            // Empty composite field (two commas next to each other)
            null
        } else if (source[start] == '"') {
            // Quoted value - escape quotes inside (") and backslash (\)
            buildString {
                var i = start + 1
                while (i < end - 1) {
                    val char = source[i]
                    if (char == '"' || char == '\\') {
                        i++
                        append(source[i])
                    } else {
                        append(char)
                    }
                    i++
                }
            }
        } else {
            // Field without quotes
            val rawValue = source.subSequence(start, end).toString()
            if (rawValue == "NULL") null else rawValue
        }
    }
}
