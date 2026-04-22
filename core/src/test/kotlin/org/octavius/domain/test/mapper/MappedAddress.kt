package org.octavius.domain.test.mapper

import org.octavius.data.annotation.PgComposite
import org.octavius.data.annotation.PgCompositeMapper

@PgComposite(name = "mapped_address", mapper = AddressMapper::class)
data class MappedAddress(val street: String, val city: String)

object AddressMapper : PgCompositeMapper<MappedAddress> {
    override fun toDataObject(map: Map<String, Any?>): MappedAddress =
        MappedAddress(
            street = map["street"] as String,
            city = map["city"] as String
        )

    override fun toDataMap(obj: MappedAddress): Map<String, Any?> = mapOf(
        "street" to obj.street,
        "city" to obj.city
    )
}

@PgComposite(name = "class_mapped_address", mapper = ClassAddressMapper::class)
data class ClassMappedAddress(val street: String, val city: String)

class ClassAddressMapper : PgCompositeMapper<ClassMappedAddress> {
    override fun toDataObject(map: Map<String, Any?>): ClassMappedAddress =
        ClassMappedAddress(
            street = map["street"] as String,
            city = map["city"] as String
        )

    override fun toDataMap(obj: ClassMappedAddress): Map<String, Any?> = mapOf(
        "street" to obj.street,
        "city" to obj.city
    )
}