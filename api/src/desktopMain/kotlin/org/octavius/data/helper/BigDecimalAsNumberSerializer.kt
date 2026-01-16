package org.octavius.data.helper

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

/**
 * JSON serializer for [BigDecimal] that preserves numeric precision.
 *
 * Encodes BigDecimal as an unquoted JSON number literal (not a string),
 * which is important for PostgreSQL's JSONB type to correctly interpret
 * the value as a number.
 *
 * ### Usage Example
 * ```kotlin
 * @Serializable
 * @DynamicallyMappable("price_data")
 * data class PriceData(
 *     @Serializable(with = BigDecimalAsNumberSerializer::class)
 *     val price: BigDecimal
 * )
 * ```
 */
object BigDecimalAsNumberSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.math.BigDecimal", PrimitiveKind.STRING)

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: BigDecimal) {
        (encoder as JsonEncoder).encodeJsonElement(JsonUnquotedLiteral(value.toPlainString()))
    }

    override fun deserialize(decoder: Decoder): BigDecimal {
        val jsonElement = (decoder as JsonDecoder).decodeJsonElement()

        val content = jsonElement.jsonPrimitive.content

        return BigDecimal(content)
    }
}