package org.octavius.data

import org.octavius.data.exception.ConversionException
import org.octavius.data.exception.ConversionExceptionMessage
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Validates whether a runtime value matches the expected Kotlin type.
 *
 * This is an internal framework function used during object mapping to ensure type safety.
 * For collections (List, Map), validates the type of the first non-null element.
 *
 * @param value The value to validate (can be null).
 * @param targetType The expected Kotlin type (KType) including generic parameters.
 * @return The original value if validation passes.
 * @throws ConversionException if the value's type doesn't match the target type.
 */
fun validateValue(value: Any?, targetType: KType): Any? {
    if (value == null) {
        return null
    }

    val targetClass = targetType.classifier as KClass<*>

    // --- Validation 1: Check main type ---
    if (!targetClass.isInstance(value)) {
        throw ConversionException(
            messageEnum = ConversionExceptionMessage.INCOMPATIBLE_TYPE,
            value = value,
            targetType = targetType.toString()
        )
    }

    // --- Validation 2: Check element type in collection ---
    when (value) {
        is List<*> -> validateList(value, targetType)
        // The only map from database that can come is JsonObject
        is Map<*, *> -> validateMap(value, targetType)
    }

    return value
}

private fun validateList(value: List<*>, targetType: KType) {
    val firstNonNullElement = value.firstOrNull { it != null }

    if (firstNonNullElement != null) {
        val listElementType = targetType.arguments.firstOrNull()?.type
            ?: return // For List<*> or when type is unknown, we don't validate further

        val listElementClass = listElementType.classifier as? KClass<*>
            ?: return // Couldn't determine element class, we give up

        if (!listElementClass.isInstance(firstNonNullElement)) {
            throw ConversionException(
                messageEnum = ConversionExceptionMessage.INCOMPATIBLE_COLLECTION_ELEMENT_TYPE,
                value = firstNonNullElement,
                targetType = listElementType.toString() // E.g., "kotlin.String"
            )
        }
    }
}

private fun validateMap(value: Map<*,*>, targetType: KType) {
    val firstNonNullEntry = value.entries.firstOrNull { it.key != null && it.value != null }

    if (firstNonNullEntry != null && targetType.arguments.size == 2) {
        val keyType = targetType.arguments[0].type ?: return
        val valueType = targetType.arguments[1].type ?: return

        val keyClass = keyType.classifier as KClass<*>
        val valueClass = valueType.classifier as KClass<*>

        // Check key and value type for the first pair
        if (!keyClass.isInstance(firstNonNullEntry.key) || !valueClass.isInstance(firstNonNullEntry.value)) {
            throw ConversionException(
                messageEnum = ConversionExceptionMessage.INCOMPATIBLE_COLLECTION_ELEMENT_TYPE,
                value = firstNonNullEntry,
                targetType = targetType.toString()
            )
        }
    }
}