package io.github.octaviusframework.db.api.serializer

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal


internal actual fun encodeBigDecimalNative(
    encoder: Encoder, value: BigDecimal
) = (encoder as JsonEncoder).encodeJsonElement(JsonUnquotedLiteral(value.toPlainString()))

internal actual fun decodeBigDecimalNative(decoder: Decoder): BigDecimal {
    val element = (decoder as JsonDecoder).decodeJsonElement()
    if (element is JsonNull) {
        throw SerializationException("Unexpected null value for non-nullable BigDecimal")
    }
    val content = element.jsonPrimitive.content
    return try {
        BigDecimal(content)
    } catch (e: NumberFormatException) {
        throw SerializationException("Invalid BigDecimal format: $content", e)
    }
}
