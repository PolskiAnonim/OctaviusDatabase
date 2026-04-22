package org.octavius.database.mapping.pgnative

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.data.builder.toColumn
import org.octavius.data.getOrThrow
import org.octavius.database.AbstractIntegrationTest
import org.octavius.domain.test.mapper.ClassMappedAddress
import org.octavius.domain.test.mapper.MappedAddress


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgCompositeMapperTest: AbstractIntegrationTest() {

    override val packagesToScan: List<String> = listOf("org.octavius.domain.test.mapper")

    override val scriptName: String = "init-pgcomposite-mapper-test-db.sql"

    @Test
    fun `should use custom object mapper for reading and writing composite type`() {
        val addr = MappedAddress("Main St", "New York")
        
        // Write
        dataAccess.rawQuery("INSERT INTO mapper_test (addr) VALUES (@addr)")
            .execute(mapOf("addr" to addr))

        // Read
        val result = dataAccess.select("addr").from("mapper_test")
            .where("addr = @addr")
            .toColumn<MappedAddress>(mapOf("addr" to addr))
            .getOrThrow()
            .first()

        assertEquals(addr, result)
    }

    @Test
    fun `should use custom class mapper for reading and writing composite type`() {
        val addr = ClassMappedAddress("Broadway", "New York")
        
        // Write
        dataAccess.rawQuery("INSERT INTO mapper_test (class_addr) VALUES (@addr)")
            .execute(mapOf("addr" to addr))

        // Read
        val result = dataAccess.select("class_addr").from("mapper_test")
            .where("class_addr = @addr")
            .toColumn<ClassMappedAddress>(mapOf("addr" to addr))
            .getOrThrow()
            .first()

        assertEquals(addr, result)
    }
}
