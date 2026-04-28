package io.github.octaviusframework.db.core.sql

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.db.api.DataAccess
import io.github.octaviusframework.db.api.builder.execute
import io.github.octaviusframework.db.api.getOrThrow
import io.github.octaviusframework.db.core.OctaviusDatabase
import io.github.octaviusframework.db.core.config.DatabaseConfig
import io.github.octaviusframework.db.core.jdbc.DefaultJdbcTransactionProvider
import io.github.octaviusframework.db.core.jdbc.JdbcTemplate
import io.github.octaviusframework.db.domain.test.weird.WeirdComposite
import io.github.octaviusframework.db.domain.test.weird.WeirdEnum
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Paths

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WeirdNamesIntegrationTest {

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

        // Drop schema if exists
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS \"weird schema.with dots\" CASCADE;")

        fun loadSql(name: String) = String(
            Files.readAllBytes(
                Paths.get(this::class.java.classLoader.getResource(name)!!.toURI())
            )
        )

        // Initialize DB with weird names
        jdbcTemplate.execute(loadSql("init-weird-names-test-db.sql"))

        dataAccess = OctaviusDatabase.fromDataSource(
            dataSource = dataSource,
            packagesToScan = listOf("io.github.octaviusframework.db.domain.test.weird"),
            dbSchemas = listOf("public", "weird schema.with dots"),
            disableCoreTypeInitialization = true
        )
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `should insert and select values with weird type names`() {
        val composite = WeirdComposite("hello.world", 42)
        val enum = WeirdEnum.Val1

        // Insert using raw query to be sure we can handle these names in parameters
        dataAccess.rawQuery("INSERT INTO \"weird schema.with dots\".weird_table (enum_val, comp_val, comp_array) VALUES (@enum, @comp, @comp_array)")
            .execute(
                "enum" to enum,
                "comp" to composite,
                "comp_array" to listOf(composite, composite)
            ).getOrThrow()

        // Select back
        val row = dataAccess.rawQuery("SELECT enum_val, comp_val, comp_array FROM \"weird schema.with dots\".weird_table WHERE id = 1")
            .toSingleStrict()
            .getOrThrow()

        assertThat(row["enum_val"]).isEqualTo(enum)
        assertThat(row["comp_val"]).isEqualTo(composite)
        assertThat(row["comp_array"]).isEqualTo(listOf(composite, composite))
    }
}