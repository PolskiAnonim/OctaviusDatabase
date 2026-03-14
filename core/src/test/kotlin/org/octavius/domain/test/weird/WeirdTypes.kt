package org.octavius.domain.test.weird

import org.octavius.data.MapKey
import org.octavius.data.annotation.PgComposite
import org.octavius.data.annotation.PgEnum

@PgEnum(name = "weird enum.with space", schema = "weird schema.with dots")
enum class WeirdEnum {
    Val1, Val2
}

@PgComposite(name = "weird composite.with \"quotes\"", schema = "weird schema.with dots")
data class WeirdComposite(
    @MapKey("field.one") val fieldOne: String,
    @MapKey("field two") val fieldTwo: Int
)
