package io.github.octaviusframework.db.api.serializer

import io.github.octaviusframework.db.api.model.BigDecimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Instant

/**
 * Creates [SerializersModule] required for Octavius features,
 * particularly for `dynamic_dto` serialization.
 *
 * This module includes serializers for:
 * - [BigDecimal] (preserving precision in JSON)
 * - [LocalDate], [LocalDateTime], [Instant] (with PostgreSQL infinity support)
 *
 * This function should be used to configure the [Json] instance
 * when working with Octavius-managed data types.
 */
fun createOctaviusSerializersModule(): SerializersModule {
    return SerializersModule {
        contextual(BigDecimal::class, BigDecimalAsNumberSerializer)
        contextual(LocalDate::class, LocalDateWithInfinitySerializer)
        contextual(LocalDateTime::class, LocalDateTimeWithInfinitySerializer)
        contextual(Instant::class, InstantWithInfinitySerializer)
    }
}

/**
 * Default [Json] instance configured with Octavius serializers.
 * 
 * Use this instance when manual serialization/deserialization of `dynamic_dto` 
 * or other database-related JSON content is required.
 */
val OctaviusJson = Json { serializersModule = createOctaviusSerializersModule() }