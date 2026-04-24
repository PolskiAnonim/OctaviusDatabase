package io.github.octaviusframework.db.api.type

import io.github.octaviusframework.db.api.annotation.DynamicallyMappable
import io.github.octaviusframework.db.api.annotation.PgComposite
import io.github.octaviusframework.db.api.exception.ConversionException
import io.github.octaviusframework.db.api.exception.ConversionExceptionMessage
import io.github.octaviusframework.db.api.serializer.OctaviusJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation

/**
 * Represents a polymorphic object for database storage, mapping to the `dynamic_dto` PostgreSQL type.
 *
 * `DynamicDto` acts as a "transport container" that bundles a type name and a JSON payload.
 * This allows PostgreSQL to store and query polymorphic data structures without requiring
 * dedicated `COMPOSITE` types. Corresponds to the `dynamic_dto` type in the database.
 *
 * ### Asymmetric Data Flow
 * - **Writing (Kotlin -> DB)**: Wrap your domain object in a `DynamicDto` using [DynamicDto.from].
 *   The framework converts this into the database's `dynamic_dto(text, jsonb)` structure.
 * - **Reading (DB -> Kotlin)**: The framework automatically unmarshals `dynamic_dto` values
 *   directly into your domain classes (annotated with [DynamicallyMappable]).
 *
 * ### Example: Writing Polymorphic Data
 * ```kotlin
 * // A legionnaire's benefit can be either a land grant or a military pension —
 * // both stored in the same 'veteran_benefit' column.
 * val grant = LandGrant(province = "Gallia Narbonensis", areraActa = BigDecimal("120.5"))
 * val dto = DynamicDto.from(grant)
 * dataAccess.insertInto("veterans").values("id" to 1, "benefit" to dto).execute()
 * ```
 *
 * @property typeName Identifier linked to a [DynamicallyMappable] class.
 * @property dataPayload The serialized state of the object as a [JsonElement].
 */
@ConsistentCopyVisibility
@PgComposite(name = "dynamic_dto", schema = "public")
data class DynamicDto private constructor(
    val typeName: String,
    val dataPayload: JsonElement
) {
    companion object {
        /**
         * Creates a [DynamicDto] instance from a domain object.
         *
         * This factory method uses reflection to find the [DynamicallyMappable] annotation
         * on the object's class to determine the `typeName` and serializes the object's 
         * properties into a JSON payload.
         *
         * @param value The object to wrap. Must be annotated with [DynamicallyMappable] 
         *              and `@Serializable`.
         * @return A constructed [DynamicDto] ready for database operations.
         * @throws ConversionException if annotations are missing or serialization fails.
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
                val jsonPayload = OctaviusJson.encodeToJsonElement(serializer, value)

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