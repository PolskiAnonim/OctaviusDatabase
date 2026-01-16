package org.octavius.data

import org.octavius.data.exception.ConversionException
import org.octavius.data.exception.ConversionExceptionMessage
import org.octavius.data.util.toSnakeCase
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.*
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Annotation used to specify a custom key for a property
 * during object to/from map conversion.
 *
 * By default, the property name is used with snake_case <-> camelCase conversion. This annotation allows overriding it,
 * which is useful when map key names should not match property names
 * e.g., userId vs user
 *
 * @property name Key name that will be used in the map.
 *
 * @see toDataObject
 * @see toMap
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class MapKey(val name: String)

// --- Shared Cache and Metadata for both conversions ---

/**
 * Stores complete, pre-computed metadata for a constructor parameter.
 * Provides efficient access without needing reflection again.
 */
private data class ConstructorParamMetadata<T : Any>(
    val parameter: KParameter,
    val property: KProperty1<T, Any?>,
    val type: KType,
    val keyName: String
)

/**
 * Stores class metadata based on its primary constructor.
 * Serves as a central cache for `toDataObject` and `toMap` operations.
 */
private data class DataObjectClassMetadata<T : Any>(
    val constructor: KFunction<T>,
    val constructorProperties: List<ConstructorParamMetadata<T>>
)

// We use one shared cache for both operations.
private val dataObjectCache = ConcurrentHashMap<KClass<*>, DataObjectClassMetadata<*>>()

/**
 * Internal function for getting or creating metadata for a given class.
 * This is the only place where expensive reflection occurs. Results are cached.
 */
@Suppress("UNCHECKED_CAST")
private fun <T : Any> getOrCreateDataObjectMetadata(kClass: KClass<T>): DataObjectClassMetadata<T> {
    return dataObjectCache.getOrPut(kClass) {
        val constructor = kClass.primaryConstructor
            ?: throw IllegalArgumentException("Class ${kClass.simpleName} must have a primary constructor.")

        // Map properties in the class by name for easy access
        val propertiesByName = kClass.memberProperties.associateBy { it.name }

        val constructorProperties = constructor.parameters.map { param ->
            val property = propertiesByName[param.name]!!

            val keyName = property.findAnnotation<MapKey>()?.name
                ?: param.name!!.toSnakeCase()

            ConstructorParamMetadata(
                parameter = param,
                property = property,
                type = param.type,
                keyName = keyName
            )
        }
        DataObjectClassMetadata(constructor, constructorProperties)
    } as DataObjectClassMetadata<T>
}

// --- Conversion FROM MAP to OBJECT ---

/**
 * Converts a map (typically a database row) to a data class instance.
 *
 * This is the preferred, type-safe way to convert query results to domain objects.
 * Uses the `reified` type parameter to automatically infer the target class.
 *
 * **Naming convention:** Keys in snake_case are automatically matched to camelCase properties.
 * Use [@MapKey] annotation to override the default mapping.
 *
 * @param T Target data class type.
 * @return New instance of T with values from the map.
 * @throws ConversionException if required properties are missing or types don't match.
 *
 * @see MapKey
 */
inline fun <reified T : Any> Map<String, Any?>.toDataObject(): T {
    return toDataObject(T::class)
}

fun <T : Any> Map<String, Any?>.toDataObject(kClass: KClass<T>): T {
    val metadata = getOrCreateDataObjectMetadata(kClass)

    val args = metadata.constructorProperties.mapNotNull { meta ->
        val (param, _, type, keyName) = meta

        val valueToUse = when {
            // Case 1: Key exists in the map. Always use the value from the map,
            // even if it's an explicit null.
            this.containsKey(keyName) -> this[keyName]
            // Case 2: Key does NOT exist in the map.
            // Parameter has a default value, so we skip it in the args map.
            // callBy() will automatically use the default value.
            // mapNotNull will remove this pair
            param.isOptional -> return@mapNotNull null
            // Case 3: Parameter has no default value and is not in the map.
            // Parameter is nullable (e.g., String?), so we can insert null.
            param.type.isMarkedNullable -> null
            // Case 4: Parameter is non-nullable (e.g., String) and has no default value,
            // and the key was not found in the map. This is an error.
            else -> throw ConversionException(
                messageEnum = ConversionExceptionMessage.MISSING_REQUIRED_PROPERTY,
                targetType = kClass.qualifiedName,
                value = keyName,
                rowData = this,
                propertyName = param.name
            )
        }

        // Validation using cached KType
        val validatedValue = try {
            validateValue(valueToUse, type)
        } catch (e: ConversionException) {
            throw ConversionException(
                messageEnum = e.messageEnum,
                value = e.value,
                targetType = e.targetType,
                rowData = this,
                propertyName = param.name
            )
        }

        param to validatedValue
    }.associate { it }

    // Call the primary constructor with prepared arguments.
    try {
        return metadata.constructor.callBy(args)
    } catch (e: Exception) {
        throw ConversionException(
            messageEnum = ConversionExceptionMessage.OBJECT_MAPPING_FAILED,
            targetType = kClass.qualifiedName ?: kClass.simpleName ?: "unknown",
            rowData = this,
            cause = e
        )
    }
}


// --- Conversion FROM OBJECT to MAP ---

/**
 * Converts a data class object to a map, where keys are property names
 * (or values from @MapKey annotation), and values are the property values.
 *
 * @param excludeKeys Keys to exclude from the resulting map
 * @return Map representing the object.
 */
fun <T : Any> T.toMap(vararg excludeKeys: String): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    val metadata = getOrCreateDataObjectMetadata(this::class) as DataObjectClassMetadata<T>

    // Convert to Set for faster checking (O(1) instead of O(n))
    val exclusionSet = if (excludeKeys.isNotEmpty()) excludeKeys.toSet() else emptySet()

    return metadata.constructorProperties.mapNotNull { meta ->
        val (_, property, _, keyName) = meta

        if (keyName in exclusionSet) {
            return@mapNotNull null
        }

        val value = property.get(this)

        keyName to value
    }.associate { it }
}
