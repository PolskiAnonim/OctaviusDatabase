package io.github.octaviusframework.db.api.serializer

import io.github.octaviusframework.db.api.util.CaseConvention
import io.github.octaviusframework.db.api.util.CaseConverter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.enums.EnumEntries

/**
 * A specialized serializer for mapping Kotlin Enums to their PostgreSQL representations
 * within `dynamic_dto`.
 *
 * This serializer is essential when your Enums use different naming conventions
 * in Kotlin (e.g., `PascalCase`) and PostgreSQL (e.g., `SNAKE_CASE_UPPER`).
 * It ensures that the value stored in the JSONB payload of a `dynamic_dto`
 * correctly matches the database's expectations.
 *
 * ### Key Features
 * - **Bidirectional Mapping**: Automatically converts names during both serialization and deserialization.
 * - **Convention Support**: Integrates with [CaseConvention] for flexible name transformations.
 *
 * ### Usage Example
 * ```kotlin
 * @Serializable(with = LegionStatusSerializer::class)
 * @PgEnum(pgConvention = CaseConvention.SNAKE_CASE_UPPER)
 * enum class LegionStatus { Garrisoned, OnMarch, InBattle, Victorious }
 *
 * object LegionStatusSerializer : DynamicDtoEnumSerializer<LegionStatus>(
 *     enumName = "LegionStatus",
 *     entries = LegionStatus.entries,
 *     pgConvention = CaseConvention.SNAKE_CASE_UPPER,
 *     kotlinConvention = CaseConvention.PASCAL_CASE
 * )
 * ```
 *
 * @param E The enum class to be serialized.
 * @param enumName A unique identifier for the enum in the serialization descriptor.
 * @param entries The list of all enum values (typically provided via `EnumClass.entries`).
 * @param pgConvention The naming convention used in the database (default: `SNAKE_CASE_UPPER`).
 * @param kotlinConvention The naming convention used in your Kotlin code (default: `PASCAL_CASE`).
 */
open class DynamicDtoEnumSerializer<E : Enum<E>>(
    enumName: String,
    private val entries: EnumEntries<E>,
    private val pgConvention: CaseConvention = CaseConvention.SNAKE_CASE_UPPER,
    private val kotlinConvention: CaseConvention = CaseConvention.PASCAL_CASE
) : KSerializer<E> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.github.octaviusframework.db.api.serializer.$enumName", PrimitiveKind.STRING)

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

        // Convert from database convention back to Kotlin convention
        val kotlinName = CaseConverter.convert(
            string,
            pgConvention,
            kotlinConvention
        )

        return entries.firstOrNull { it.name == kotlinName }
            ?: throw SerializationException("Unknown $descriptor name: $string (mapped from $kotlinName)")
    }
}