package org.octavius.data.helper

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.octavius.data.util.CaseConvention
import org.octavius.data.util.CaseConverter

/**
 * Base serializer for enum values used within [DynamicDto][org.octavius.data.type.DynamicDto].
 *
 * Handles conversion between Kotlin enum names and PostgreSQL storage conventions.
 * Extend this class to create a serializer for your specific enum type.
 *
 * ### Usage Example
 * ```kotlin
 * @Serializable(with = OrderStatusSerializer::class)
 * @PgEnum(pgConvention = CaseConvention.SNAKE_CASE_UPPER)
 * enum class OrderStatus { Pending, InProgress, Completed }
 *
 * object OrderStatusSerializer : DynamicDtoEnumSerializer<OrderStatus>(
 *     serialName = "OrderStatus",
 *     entries = OrderStatus.entries,
 *     pgConvention = CaseConvention.SNAKE_CASE_UPPER,
 *     kotlinConvention = CaseConvention.PASCAL_CASE
 * )
 * ```
 *
 * @param E The enum type to serialize.
 * @param serialName Name used in the serialization descriptor.
 * @param entries List of all enum values (use `EnumClass.entries`).
 * @param pgConvention Naming convention used in PostgreSQL (e.g., 'PENDING', 'in_progress').
 * @param kotlinConvention Naming convention used in Kotlin enum (e.g., Pending, InProgress).
 */
open class DynamicDtoEnumSerializer<E : Enum<E>>(
    serialName: String,
    private val entries: List<E>, // Pass the list of values
    // How values are stored in PostgreSQL database (e.g., 'PENDING', 'in_progress')
    private val pgConvention: CaseConvention = CaseConvention.SNAKE_CASE_UPPER,
    // How values are stored in Kotlin Enum class (e.g., Pending, InProgress)
    private val kotlinConvention: CaseConvention = CaseConvention.PASCAL_CASE
) : KSerializer<E> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: E) {
        val pgName = CaseConverter.convert(
            value.name,
            kotlinConvention,
            pgConvention
        )
        encoder.encodeString(pgName)
    }

    override fun deserialize(decoder: Decoder): E {
        val string = decoder.decodeString()

        // Reverse logic is also here ONCE
        // Note: This can be optimized with a map if performance is critical,
        // but for enums this is usually negligible.
        val kotlinName = CaseConverter.convert(
            string,
            pgConvention,
            kotlinConvention
        )

        return entries.firstOrNull { it.name == kotlinName }
            ?: throw SerializationException("Unknown $descriptor name: $string")
    }
}