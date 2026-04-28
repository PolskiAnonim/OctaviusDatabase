package io.github.octaviusframework.db.core.mapping.pgnative

import io.github.octaviusframework.db.api.builder.toColumn
import io.github.octaviusframework.db.api.getOrThrow
import io.github.octaviusframework.db.core.AbstractIntegrationTest
import io.github.octaviusframework.db.domain.test.mapper.ClassMappedAddress
import io.github.octaviusframework.db.domain.test.mapper.MappedAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test


class PgCompositeMapperTest: AbstractIntegrationTest() {

    override val packagesToScan: List<String> = listOf("io.github.octaviusframework.db.domain.test.mapper")

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
