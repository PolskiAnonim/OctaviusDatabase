package org.octavius.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.data.DataAccess
import org.octavius.data.builder.toFieldStrict
import org.octavius.data.getOrThrow
import org.octavius.database.config.DatabaseConfig
import org.octavius.database.jdbc.DefaultJdbcTransactionProvider
import org.octavius.database.jdbc.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Paths

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsonOperatorIntegrationTest {

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
        val jdbcTemplate = JdbcTemplate(DefaultJdbcTransactionProvider(dataSource))

        // Drop table if exists
        jdbcTemplate.execute("DROP TABLE IF EXISTS json_test CASCADE;")

        fun loadSql(name: String) = String(
            Files.readAllBytes(
                Paths.get(this::class.java.classLoader.getResource(name)!!.toURI())
            )
        )

        // Initialize DB
        jdbcTemplate.execute(loadSql("init-json-operators-test-db.sql"))

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
    fun `should handle jsonb exist operator '?'`() {
        // This query contains '?' which JDBC might mistake for a parameter
        val result = dataAccess.rawQuery("SELECT count(*) as count FROM json_test WHERE data ? 'a'")
            .toFieldStrict<Long>()
            .getOrThrow()

        assertThat(result).isEqualTo(1L)
    }

    @Test
    fun `should handle jsonb exist any operator '?|'`() {
        val result = dataAccess.rawQuery("SELECT count(*) as count FROM json_test WHERE data ?| array['a', 'c']")
            .toFieldStrict<Long>()
            .getOrThrow()

        // Rows: {"a": 1, "b": 2} (has a), {"b": 2, "c": 3} (has c)
        assertThat(result).isEqualTo(2L)
    }

    @Test
    fun `should handle jsonb exist all operator '?&'`() {
        val result = dataAccess.rawQuery("SELECT count(*) as count FROM json_test WHERE data ?& array['a', 'b']")
            .toFieldStrict<Long>()
            .getOrThrow()

        // Rows: {"a": 1, "b": 2} (has a and b)
        assertThat(result).isEqualTo(1L)
    }

    @Test
    fun `should handle mixing jsonb operators with named parameters`() {
        val result = dataAccess.rawQuery("SELECT count(*) as count FROM json_test WHERE data ? @key AND id = @id")
            .toFieldStrict<Long>("key" to "a", "id" to 1)
            .getOrThrow()

        assertThat(result).isEqualTo(1L)
    }
}
