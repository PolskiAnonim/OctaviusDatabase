package org.octavius.domain.test.json

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import kotlin.time.Instant

@Serializable
data class Product(
    val id: Int? = null,
    val name: String,
    @Contextual val price: BigDecimal,
    val tags: List<String>? = null,
    @SerialName("release_date")
    @Contextual val releaseDate: LocalDate? = null,
    @SerialName("created_at")
    @Contextual val createdAt: Instant? = null,
    @SerialName("delivery_time")
    val deliveryTime: LocalTime? = null
)

