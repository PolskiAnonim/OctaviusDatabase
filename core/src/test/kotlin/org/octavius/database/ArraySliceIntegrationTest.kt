package org.octavius.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.octavius.data.DataAccess
import org.octavius.data.builder.toSingleStrict
import org.octavius.data.getOrThrow
import org.octavius.database.config.DatabaseConfig
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Paths

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArraySliceIntegrationTest {

    private lateinit var dataAccess: DataAccess
    private lateinit var dataSource: HikariDataSource

    @BeforeAll
    fun setup() {
        val databaseConfig = DatabaseConfig.loadFromFile("test-database.properties")

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = databaseConfig.dbUrl
            username = databaseConfig.dbUsername
            password = databaseConfig.dbPassword
        }
        dataSource = HikariDataSource(hikariConfig)
        val jdbcTemplate = JdbcTemplate(dataSource)

        // Drop table if exists
        jdbcTemplate.execute("DROP TABLE IF EXISTS array_test_table CASCADE;")

        fun loadSql(name: String) = String(
            Files.readAllBytes(
                Paths.get(this::class.java.classLoader.getResource(name)!!.toURI())
            )
        )

        // Initialize DB
        jdbcTemplate.execute(loadSql("init-array-slice-test-db.sql"))

        dataAccess = OctaviusDatabase.fromDataSource(
            dataSource = dataSource,
            packagesToScan = emptyList(),
            dbSchemas = listOf("public"),
            disableFlyway = true
        )
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `should handle array index access with parameter`() {
        // SELECT int_array[@idx] FROM array_test_table WHERE id = 1
        val result = dataAccess.rawQuery("SELECT int_array[@idx] as val FROM array_test_table WHERE id = 1")
            .toSingleStrict(mapOf("idx" to 2))
            .getOrThrow()

        assertThat(result["val"] as Int).isEqualTo(20)
    }

    @Test
    fun `should handle array slice with two parameters`() {
        // SELECT int_array[@start:@end] FROM array_test_table WHERE id = 1
        val result = dataAccess.rawQuery("SELECT int_array[@start:@end] as slice FROM array_test_table WHERE id = 1")
            .toSingleStrict(mapOf("start" to 2, "end" to 4))
            .getOrThrow()

        assertThat(result["slice"] as List<*>).containsExactly(20, 30, 40)
    }

    @Test
    fun `should handle array slice with only upper bound parameter`() {
        // SELECT int_array[:@end] FROM array_test_table WHERE id = 1
        val result = dataAccess.rawQuery("SELECT int_array[:@end] as slice FROM array_test_table WHERE id = 1")
            .toSingleStrict(mapOf("end" to 3))
            .getOrThrow()

        assertThat(result["slice"] as List<*>).containsExactly(10, 20, 30)
    }

    @Test
    fun `should handle array slice with only lower bound parameter`() {
        // SELECT int_array[:index] FROM array_test_table WHERE id = 1
        val result = dataAccess.rawQuery("SELECT int_array[:index] as slice, @index as ind  FROM array_test_table WHERE id = 1")
            .toSingleStrict("index" to 1)
            .getOrThrow()

        assertThat(result["slice"] as List<*>).containsExactly(10, 20, 30)
        assertThat(result["ind"] as Int).isEqualTo(1)
    }

    @Test
    fun `should handle complex array slice with expressions and parameters`() {
        // SELECT int_array[@off + 1 : @limit * 2] FROM array_test_table WHERE id = 1
        // (1 + 1 : 2 * 2) -> (2 : 4)
        val result = dataAccess.rawQuery("SELECT int_array[@off + 1 : @limit * 2] as slice FROM array_test_table WHERE id = 1")
            .toSingleStrict(mapOf("off" to 1, "limit" to 2))
            .getOrThrow()

        assertThat(result["slice"] as List<*>).containsExactly(20, 30, 40)
    }
}
