package org.octavius.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.data.DataAccess
import org.octavius.data.builder.toSingleStrict
import org.octavius.data.getOrThrow
import org.octavius.data.serializer.OctaviusJson
import org.octavius.database.config.DatabaseConfig
import org.octavius.database.jdbc.DefaultJdbcTransactionProvider
import org.octavius.database.jdbc.JdbcTemplate
import org.octavius.domain.test.json.ProductComposite
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsonCompositeIntegrationTest {

    private lateinit var dataAccess: DataAccess
    private lateinit var dataSource: HikariDataSource

    @BeforeAll
    fun setup() {
        val databaseConfig = DatabaseConfig.loadFromFile("test-database.properties")
        
        dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = databaseConfig.dbUrl
            username = databaseConfig.dbUsername
            password = databaseConfig.dbPassword
        })
        val jdbcTemplate = JdbcTemplate(DefaultJdbcTransactionProvider(dataSource))

        jdbcTemplate.execute("DROP SCHEMA IF EXISTS public CASCADE;")
        jdbcTemplate.execute("CREATE SCHEMA public;")
        
        fun loadSql(name: String) = String(
            Files.readAllBytes(
                Paths.get(this::class.java.classLoader.getResource(name)!!.toURI())
            )
        )
        jdbcTemplate.execute(loadSql("init-json-composite-test-db.sql"))

        dataAccess = OctaviusDatabase.fromDataSource(
            dataSource = dataSource,
            packagesToScan = listOf("org.octavius.domain.test.json"),
            dbSchemas = databaseConfig.dbSchemas,
            disableFlyway = true,
            disableCoreTypeInitialization = false
        )
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `should map table row to JSON and then to ProductComposite with date-time types`() {
        // Query returning JSONB from table row
        val sql = "SELECT row_to_json(p)::jsonb as product_json FROM products p WHERE name = 'Laptop'"
        
        val result = dataAccess.rawQuery(sql)
            .toSingleStrict()
            .getOrThrow()
        
        // Extract JSON string from result
        val jsonString = result["product_json"].toString()
        
        // Deserialize using OctaviusJson
        val product = OctaviusJson.decodeFromString<ProductComposite>(jsonString)
        
        assertThat(product.name).isEqualTo("Laptop")
        assertThat(product.price).isEqualByComparingTo(BigDecimal("999.99"))
        assertThat(product.tags).containsExactly("electronics", "work")
        assertThat(product.releaseDate).isEqualTo(LocalDate(2024, 1, 1))
        assertThat(product.createdAt).isEqualTo(Instant.parse("2024-01-01T12:00:00Z"))
        assertThat(product.deliveryTime).isEqualTo(LocalTime(10, 0, 0))
    }

    @Test
    fun `should map manually built JSON to ProductComposite`() {
        val sql = """
            SELECT jsonb_build_object(
                'id', 10,
                'name', 'Mechanical Keyboard',
                'price', 150.00,
                'tags', ARRAY['electronics', 'peripheral'],
                'release_date', '2023-12-25',
                'created_at', '2023-12-25T15:00:00Z',
                'delivery_time', '09:00:00'
            ) as product_json
        """.trimIndent()
        
        val result = dataAccess.rawQuery(sql)
            .toSingleStrict()
            .getOrThrow()
            
        val jsonString = result["product_json"].toString()
        val product = OctaviusJson.decodeFromString<ProductComposite>(jsonString)
        
        assertThat(product.id).isEqualTo(10)
        assertThat(product.name).isEqualTo("Mechanical Keyboard")
        assertThat(product.price).isEqualByComparingTo(BigDecimal("150.00"))
        assertThat(product.tags).containsExactly("electronics", "peripheral")
        assertThat(product.releaseDate).isEqualTo(LocalDate(2023, 12, 25))
        assertThat(product.createdAt).isEqualTo(Instant.parse("2023-12-25T15:00:00Z"))
        assertThat(product.deliveryTime).isEqualTo(LocalTime(9, 0, 0))
    }

    @Test
    fun `should serialize ProductComposite with dates to JSON and use it in PostgreSQL`() {
        val product = ProductComposite(
            name = "Mouse",
            price = BigDecimal("25.50"),
            tags = listOf("peripheral"),
            releaseDate = LocalDate(2024, 2, 10),
            createdAt = Instant.DISTANT_PAST,
            deliveryTime = LocalTime(18, 30, 0)
        )
        
        // Serialize to JSON string
        val jsonString = OctaviusJson.encodeToString(product)
        
        // Use json_populate_record to turn JSON back into a product row and verify fields
        val sql = """
            SELECT * FROM json_populate_record(NULL::products, @json::json)
        """.trimIndent()
        
        val result = dataAccess.rawQuery(sql)
            .toSingleStrict("json" to jsonString)
            .getOrThrow()
            
        assertThat(result["name"]).isEqualTo("Mouse")
        assertThat(result["price"]).isEqualTo(BigDecimal("25.50"))
        assertThat(result["release_date"]).isEqualTo(LocalDate.parse("2024-02-10"))
        assertThat(result["created_at"]).isEqualTo(Instant.DISTANT_PAST)
        assertThat(result["delivery_time"]).isEqualTo(LocalTime.parse("18:30"))
    }
}
