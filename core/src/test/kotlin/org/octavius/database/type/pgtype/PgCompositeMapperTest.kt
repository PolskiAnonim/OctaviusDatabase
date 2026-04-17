package org.octavius.database.type.pgtype

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.data.DataAccess
import org.octavius.data.annotation.PgComposite
import org.octavius.data.annotation.PgCompositeMapper
import org.octavius.data.builder.toColumn
import org.octavius.data.getOrThrow
import org.octavius.database.OctaviusDatabase
import org.octavius.database.config.DatabaseConfig
import org.octavius.database.config.DynamicDtoSerializationStrategy
import org.octavius.database.jdbc.JdbcTemplate
import org.octavius.database.jdbc.SpringJdbcTransactionProvider

@PgComposite(name = "mapped_address", mapper = AddressMapper::class)
data class MappedAddress(val street: String, val city: String)

object AddressMapper : PgCompositeMapper<MappedAddress> {
    override fun toDataObject(map: Map<String, Any?>): MappedAddress = MappedAddress(
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
    override fun toDataObject(map: Map<String, Any?>): ClassMappedAddress = ClassMappedAddress(
        street = map["street"] as String,
        city = map["city"] as String
    )

    override fun toDataMap(obj: ClassMappedAddress): Map<String, Any?> = mapOf(
        "street" to obj.street,
        "city" to obj.city
    )
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgCompositeMapperTest {
    private lateinit var dataAccess: DataAccess
    private lateinit var dataSource: HikariDataSource

    @BeforeAll
    fun setup() {
        val config = DatabaseConfig.loadFromFile("test-database.properties")
        dataSource = HikariDataSource().apply {
            jdbcUrl = config.dbUrl
            username = config.dbUsername
            password = config.dbPassword
        }

        val jdbcTemplate = JdbcTemplate(SpringJdbcTransactionProvider(dataSource))
        jdbcTemplate.execute("DROP TABLE IF EXISTS mapper_test CASCADE")
        jdbcTemplate.execute("DROP TYPE IF EXISTS mapped_address CASCADE")
        jdbcTemplate.execute("DROP TYPE IF EXISTS class_mapped_address CASCADE")
        jdbcTemplate.execute("CREATE TYPE mapped_address AS (street TEXT, city TEXT)")
        jdbcTemplate.execute("CREATE TYPE class_mapped_address AS (street TEXT, city TEXT)")
        jdbcTemplate.execute("CREATE TABLE mapper_test (id SERIAL PRIMARY KEY, addr mapped_address, class_addr class_mapped_address)")

        dataAccess = OctaviusDatabase.fromDataSource(
            dataSource,
            listOf("org.octavius.database.type.pgtype"),
            config.dbSchemas,
            DynamicDtoSerializationStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS,
            disableFlyway = true
        )
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

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
