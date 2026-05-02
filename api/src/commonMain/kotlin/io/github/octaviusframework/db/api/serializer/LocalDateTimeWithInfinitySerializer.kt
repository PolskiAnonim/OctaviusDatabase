package io.github.octaviusframework.db.api.serializer

import io.github.octaviusframework.db.api.type.DISTANT_FUTURE
import io.github.octaviusframework.db.api.type.DISTANT_PAST
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializer for [LocalDateTime] that supports PostgreSQL's `infinity` and `-infinity` values.
 *
 * This serializer ensures that [LocalDateTime.Companion.DISTANT_FUTURE] and [LocalDateTime.Companion.DISTANT_PAST]
 * are correctly converted to `infinity` and `-infinity` strings respectively,
 * which are understood by PostgreSQL.
 */
object LocalDateTimeWithInfinitySerializer : KSerializer<LocalDateTime> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.github.octaviusframework.db.api.serializer.LocalDateTimeWithInfinitySerializer", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDateTime) {
        when (value) {
            LocalDateTime.DISTANT_FUTURE -> encoder.encodeString("infinity")
            LocalDateTime.DISTANT_PAST -> encoder.encodeString("-infinity")
            else -> encoder.encodeString(value.toString())
        }
    }

    override fun deserialize(decoder: Decoder): LocalDateTime {
        return when (val string = decoder.decodeString()) {
            "infinity" -> LocalDateTime.DISTANT_FUTURE
            "-infinity" -> LocalDateTime.DISTANT_PAST
            else -> LocalDateTime.parse(string)
        }
    }
}