package org.octavius.data.exception

enum class ConversionExceptionMessage {
    /** General standard type conversion error */
    VALUE_CONVERSION_FAILED,
    /** Database value doesn't match any enum */
    ENUM_CONVERSION_FAILED,
    UNSUPPORTED_COMPONENT_TYPE_IN_ARRAY,
    /** dynamic_dto parsing error */
    INVALID_DYNAMIC_DTO_FORMAT,
    INCOMPATIBLE_COLLECTION_ELEMENT_TYPE,
    INCOMPATIBLE_TYPE,

    // Mapping errors
    /** General error during data class instantiation */
    OBJECT_MAPPING_FAILED,
    /** Missing key for required field in data class */
    MISSING_REQUIRED_PROPERTY,
    /** JSON deserialization error in dynamic_dto */
    JSON_DESERIALIZATION_FAILED,
    /** Object to JSON serialization error for dynamic_dto */
    JSON_SERIALIZATION_FAILED,
    /** When a non-null value was expected but null was received */
    NON_NULL_ASSERTION_FAILED
}

private fun generateDeveloperMessage(
    messageEnum: ConversionExceptionMessage,
    value: Any?,
    targetType: String?,
    propertyName: String?
): String {
    return when (messageEnum) {
        ConversionExceptionMessage.VALUE_CONVERSION_FAILED -> "Cannot convert value '$value' to type '$targetType'."
        ConversionExceptionMessage.ENUM_CONVERSION_FAILED -> "Cannot convert enum value '$value' to type '$targetType'."
        ConversionExceptionMessage.INVALID_DYNAMIC_DTO_FORMAT -> "Invalid dynamic_dto format: '$value'."
        ConversionExceptionMessage.INCOMPATIBLE_COLLECTION_ELEMENT_TYPE ->
            "An element within a collection has an incorrect type. Expected elements compatible with '$targetType', but found an element of type '${value?.let { it::class.simpleName }}'."

        ConversionExceptionMessage.INCOMPATIBLE_TYPE -> "Element has an incompatible type. Expected elements compatible with '$targetType', but found an element of type '${value?.let { it::class.simpleName }}'."
        ConversionExceptionMessage.OBJECT_MAPPING_FAILED -> "Failed to map data to object of class '$targetType'."
        ConversionExceptionMessage.MISSING_REQUIRED_PROPERTY -> "Missing required field '$propertyName' (key: '$value') when mapping to class '$targetType'."
        ConversionExceptionMessage.JSON_DESERIALIZATION_FAILED -> "Failed to deserialize JSON for dynamic type '$targetType'."
        ConversionExceptionMessage.UNSUPPORTED_COMPONENT_TYPE_IN_ARRAY ->
            "Native JDBC arrays (Array<*>) do not support complex types (e.g., data class, List, Map). " +
                    "Detected type: '${targetType}'. Use List<DataClass> so the library can generate ARRAY[ROW(...)] syntax."

        ConversionExceptionMessage.JSON_SERIALIZATION_FAILED -> "Failed to serialize object of class '$targetType' to JSON format. " +
                "Ensure that the class and all its nested types have the @Serializable annotation."
        ConversionExceptionMessage.NON_NULL_ASSERTION_FAILED ->
            "A non-null value was expected, but null was received. This typically happens when using .assertNotNull() on a query result that was null."
    }
}

/**
 * Errors related to conversion, parsing, or mapping data between Postgres and Kotlin.
 */
class ConversionException(
    val messageEnum: ConversionExceptionMessage,
    // Context fields - can be null depending on error type
    val value: Any? = null,
    val targetType: String? = null,
    val rowData: Map<String, Any?>? = null,
    val propertyName: String? = null,
    cause: Throwable? = null
) : DatabaseException(
    messageEnum.name,
    cause
) {
    override fun toString(): String {

        var s = """

        -------------------------------
        |     CONVERSION FAILED     
        | message: ${generateDeveloperMessage(this.messageEnum, value, targetType, propertyName)}
        | value: $value
        | targetType: $targetType
        | rowData: $rowData
        | propertyName: $propertyName
        """.trimIndent()
        if (cause != null) {
            s += "\n| Caused by: ${cause!!::class.simpleName}: ${cause!!.message}"
        }
        return s + """
        ---------------------------------
        """.trimIndent()
    }
}