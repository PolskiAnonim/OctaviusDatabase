package org.octavius.domain.test.dynamic

import kotlinx.serialization.Serializable
import org.octavius.data.annotation.DynamicallyMappable

// Główny obiekt, który będzie "konsumował" dynamiczne DTO
data class UserWithDynamicProfile(
    val userId: Int,
    val username: String,
    val profile: DynamicProfile // To pole zostanie zmapowane z dynamic_dto
)

// Nasz dynamiczny, zagnieżdżony obiekt
@Serializable // Wymagane przez kotlinx.serialization
@DynamicallyMappable("profile_dto")
data class DynamicProfile(
    val role: String,
    val permissions: List<String>,
    val lastLogin: String // Użyjemy stringa dla uproszczenia w teście
)

// Drugi, inny dynamiczny obiekt do testowania polimorfizmu
@Serializable
@DynamicallyMappable("user_stats_dto")
data class UserStats(
    val postCount: Int,
    val commentCount: Int
)