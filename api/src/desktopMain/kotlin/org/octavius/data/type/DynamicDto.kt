package org.octavius.data.type

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import org.octavius.data.annotation.DynamicallyMappable
import org.octavius.data.annotation.PgComposite
import org.octavius.data.exception.ConversionException
import org.octavius.data.exception.ConversionExceptionMessage
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation

/**
 * Represents a polymorphic object ready for database storage.
 *
 * This class is a public API and "transport container" that unambiguously
 * wraps any object marked with the [DynamicallyMappable] annotation into a structure
 * understandable by the framework and PostgreSQL database. Corresponds to the
 * `dynamic_dto` type in the database.
 *
 * Serves as an explicit, controlled way to prepare polymorphic data for writing,
 * providing an alternative to the framework's fully automatic conversion mechanism.
 *
 * **Write and Read Asymmetry:**
 * - **On write:** You use this class (or its `from()` factory) to "pack"
 *   your domain object.
 * - **On read:** The framework automatically "unpacks" data and returns
 *   directly the domain object (e.g., `DynamicProfile`), not a `DynamicDto` instance.
 *
 * @property typeName Key identifying the object type, retrieved from the [DynamicallyMappable] annotation.
 * @property dataPayload Object serialized to [JsonElement] form.
 * @see DynamicallyMappable
 */
@ConsistentCopyVisibility
@PgComposite(name = "dynamic_dto")
data class DynamicDto private constructor(
    val typeName: String,
    val dataPayload: JsonElement
) {
    companion object {
        /**
         * Convenient factory method for creating a [DynamicDto] instance from a domain object.
         *
         * This is the preferred path for end users. Uses reflection to
         * automatically find the `typeName` from the [DynamicallyMappable] annotation
         * and serializes the object to [JsonElement].
         *
         * @param value Object instance to wrap. Must have the
         *              [DynamicallyMappable] and `@Serializable` annotations.
         * @return New, fully constructed [DynamicDto] instance.
         * @throws ConversionException if the object's class doesn't have the required annotation
         *                           or if an error occurs during JSON serialization.
         */
        inline fun <reified T: Any> from(value: T): DynamicDto {
            @Suppress("UNCHECKED_CAST")
            val kClass = value::class as KClass<Any>
            // 1. Find type name (reflection)
            val annotation = kClass.findAnnotation<DynamicallyMappable>()
                ?: throw ConversionException(
                    messageEnum = ConversionExceptionMessage.JSON_SERIALIZATION_FAILED,
                    value = kClass.simpleName,
                    targetType = DynamicallyMappable::class.simpleName
                )

            // 2. Find serializer
            val serializer = try {
                // This is safer than other methods (read won't allow full information anyway)
                serializer<T>()
            } catch (e: Exception) {
                throw ConversionException(
                    messageEnum = ConversionExceptionMessage.JSON_SERIALIZATION_FAILED,
                    targetType = annotation.typeName,
                    cause = e
                )
            }

            // 3. Delegate to optimized version
            @Suppress("UNCHECKED_CAST")
            return from(value, annotation.typeName, serializer as KSerializer<Any>)
        }

        /**
         * [FRAMEWORK PATH]
         * Creates DTO using an externally provided (cached) serializer.
         * Zero reflection, maximum performance.
         */
        fun from(value: Any, typeName: String, serializer: KSerializer<Any>): DynamicDto {
            try {
                // Serialization to JsonElement
                val jsonPayload = Json.encodeToJsonElement(serializer, value)

                return DynamicDto(typeName, jsonPayload)
            } catch (e: Exception) {
                throw ConversionException(
                    messageEnum = ConversionExceptionMessage.JSON_SERIALIZATION_FAILED,
                    targetType = typeName,
                    cause = e
                )
            }
        }
    }
}