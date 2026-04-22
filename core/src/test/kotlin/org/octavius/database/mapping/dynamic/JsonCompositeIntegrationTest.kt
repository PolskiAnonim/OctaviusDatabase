package org.octavius.database.mapping.dynamic

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.data.builder.toSingleStrict
import org.octavius.data.getOrThrow
import org.octavius.data.serializer.OctaviusJson
import org.octavius.data.type.PgStandardType
import org.octavius.data.type.withPgType
import org.octavius.database.AbstractIntegrationTest
import org.octavius.domain.test.json.Product
import java.math.BigDecimal
import kotlin.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsonCompositeIntegrationTest : AbstractIntegrationTest() {

    override val packagesToScan: List<String> = listOf("org.octavius.domain.test.json")

    override val sqlToExecuteOnSetup: String = """
        CREATE TABLE products (
            id SERIAL PRIMARY KEY,
            name TEXT NOT NULL,
            price NUMERIC(10, 2) NOT NULL,
            tags TEXT[],
            release_date DATE,
            created_at TIMESTAMPTZ,
            delivery_time TIME
        );

        INSERT INTO products (name, price, tags, release_date, created_at, delivery_time) VALUES 
        ('Laptop', 999.99, ARRAY['electronics', 'work'], '2024-01-01', '2024-01-01 12:00:00+00', '10:00:00'),
        ('Coffee Mug', 12.50, ARRAY['kitchen', 'home'], '2023-05-15', '2023-05-15 08:30:00+00', '14:30:00');
    """.trimIndent()

    @Test
    fun `should map table row to JSON and then to Product with date-time types`() {
        // Query returning JSONB from table row
        val sql = "SELECT row_to_json(p)::jsonb as product_json FROM products p WHERE name = 'Laptop'"

        val result = dataAccess.rawQuery(sql)
            .toSingleStrict()
            .getOrThrow()

        // Extract JSON string from result
        val jsonString = result["product_json"].toString()

        // Deserialize using OctaviusJson
        val product = OctaviusJson.decodeFromString<Product>(jsonString)

        Assertions.assertThat(product.name).isEqualTo("Laptop")
        Assertions.assertThat(product.price).isEqualByComparingTo(BigDecimal("999.99"))
        Assertions.assertThat(product.tags).containsExactly("electronics", "work")
        Assertions.assertThat(product.releaseDate).isEqualTo(LocalDate(2024, 1, 1))
        Assertions.assertThat(product.createdAt).isEqualTo(Instant.parse("2024-01-01T12:00:00Z"))
        Assertions.assertThat(product.deliveryTime).isEqualTo(LocalTime(10, 0, 0))
    }

    @Test
    fun `should map manually built JSON to Product`() {
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

        val json = result["product_json"] as JsonElement
        val product = OctaviusJson.decodeFromJsonElement<Product>(json)

        Assertions.assertThat(product.id).isEqualTo(10)
        Assertions.assertThat(product.name).isEqualTo("Mechanical Keyboard")
        Assertions.assertThat(product.price).isEqualByComparingTo(BigDecimal("150.00"))
        Assertions.assertThat(product.tags).containsExactly("electronics", "peripheral")
        Assertions.assertThat(product.releaseDate).isEqualTo(LocalDate(2023, 12, 25))
        Assertions.assertThat(product.createdAt).isEqualTo(Instant.parse("2023-12-25T15:00:00Z"))
        Assertions.assertThat(product.deliveryTime).isEqualTo(LocalTime(9, 0, 0))
    }

    @Test
    fun `should serialize Product with dates to JSON and use it in PostgreSQL`() {
        val product = Product(
            name = "Mouse",
            price = BigDecimal("25.50"),
            tags = listOf("peripheral"),
            releaseDate = LocalDate(2024, 2, 10),
            createdAt = Instant.DISTANT_PAST,
            deliveryTime = LocalTime(18, 30, 0)
        )

        // Serialize to JSON string
        val json = OctaviusJson.encodeToJsonElement(product)

        // Use json_populate_record to turn JSON back into a product row and verify fields
        val sql = """
            SELECT * FROM json_populate_record(NULL::products, @json)
        """.trimIndent()

        val result = dataAccess.rawQuery(sql)
            .toSingleStrict("json" to json.withPgType(PgStandardType.JSON))
            .getOrThrow()

        Assertions.assertThat(result["name"]).isEqualTo("Mouse")
        Assertions.assertThat(result["price"]).isEqualTo(BigDecimal("25.50"))
        Assertions.assertThat(result["release_date"]).isEqualTo(LocalDate.parse("2024-02-10"))
        Assertions.assertThat(result["created_at"]).isEqualTo(Instant.DISTANT_PAST)
        Assertions.assertThat(result["delivery_time"]).isEqualTo(LocalTime.parse("18:30"))
    }
}