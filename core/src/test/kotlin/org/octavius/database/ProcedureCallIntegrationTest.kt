package org.octavius.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.data.DataAccess
import org.octavius.data.DataResult
import org.octavius.data.builder.execute
import org.octavius.data.getOrThrow
import org.octavius.database.config.DatabaseConfig
import org.octavius.domain.test.pgtype.TestPerson
import org.octavius.domain.test.pgtype.TestStatus
import java.nio.file.Files
import java.nio.file.Paths

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProcedureCallIntegrationTest {

    private lateinit var dataAccess: DataAccess

    @BeforeAll
    fun setup() {
        val databaseConfig = DatabaseConfig.loadFromFile("test-database.properties")

        val connectionUrl = databaseConfig.dbUrl
        val dbName = connectionUrl.substringAfterLast("/")
        if (!connectionUrl.contains("localhost:5432") || dbName != "octavius_test") {
            throw IllegalStateException(
                "ABORTING TEST! Attempting to run tests on a non-test database. URL: '$connectionUrl'"
            )
        }

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = databaseConfig.dbUrl
            username = databaseConfig.dbUsername
            password = databaseConfig.dbPassword
        }
        val dataSource = HikariDataSource(hikariConfig)
        val jdbcTemplate = org.springframework.jdbc.core.JdbcTemplate(dataSource)

        // Init schema with types + procedures
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS public CASCADE;")
        jdbcTemplate.execute("CREATE SCHEMA public;")

        fun loadSql(name: String) = String(
            Files.readAllBytes(
                Paths.get(this::class.java.classLoader.getResource(name)!!.toURI())
            )
        )

        jdbcTemplate.execute(loadSql("init-complex-test-db.sql"))
        jdbcTemplate.execute(loadSql("init-procedure-test-db.sql"))

        dataAccess = OctaviusDatabase.fromDataSource(
            dataSource = dataSource,
            packagesToScan = listOf("org.octavius.domain.test.pgtype"),
            dbSchemas = databaseConfig.dbSchemas,
            disableFlyway = true,
            disableCoreTypeInitialization = true
        )
    }

    @Test
    fun `should call procedure with no OUT params`() {
        val result = dataAccess.call("void_proc").execute("p_text" to "hello")

        assertThat(result).isInstanceOf(DataResult.Success::class.java)
        assertThat(result.getOrThrow()).isEmpty()
    }

    @Test
    fun `should call procedure with IN and OUT params`() {
        val result = dataAccess.call("add_numbers").execute("a" to 17, "b" to 25)

        assertThat(result.getOrThrow()).containsEntry("result", 42)
    }

    @Test
    fun `should call procedure with multiple OUT params`() {
        val result = dataAccess.call("split_text").execute("input" to "abcdef")

        val out = result.getOrThrow()
        assertThat(out).containsEntry("first_half", "abc")
        assertThat(out).containsEntry("second_half", "def")
        assertThat(out).containsEntry("total_len", 6)
    }

    @Test
    fun `should call procedure with INOUT param`() {
        val result = dataAccess.call("increment").execute("counter" to 10, "step" to 3)

        assertThat(result.getOrThrow()).containsEntry("counter", 13)
    }

    @Test
    fun `should call procedure with array IN and OUT - index tracking`() {
        val result = dataAccess.call("sum_array").execute("numbers" to listOf(10, 20, 30))

        assertThat(result.getOrThrow()).containsEntry("total", 60)
    }

    @Test
    fun `should call procedure with composite IN and OUT - ROW index tracking`() {
        val person = TestPerson("Alice", 30, "alice@test.com", true, listOf("admin"))

        val result = dataAccess.call("greet_person").execute("person" to person)

        assertThat(result.getOrThrow()).containsEntry("greeting", "Hello, Alice! Age: 30")
    }

    @Test
    fun `should call procedure with enum IN and OUT`() {
        val result = dataAccess.call("next_status").execute("current_status" to TestStatus.Pending)

        assertThat(result.getOrThrow()["next"]).isEqualTo(TestStatus.Active)
    }

    @Test
    fun `should call procedure with composite + array IN and OUT - complex index tracking`() {
        val person = TestPerson("Bob", 25, "bob@test.com", true, emptyList())

        val result = dataAccess.call("complex_proc").execute(
            "person" to person,
            "tags" to listOf("dev", "senior")
        )

        assertThat(result.getOrThrow()).containsEntry("summary", "Bob [dev, senior]")
    }

    @Test
    fun `should call procedure with map overload`() {
        val result = dataAccess.call("add_numbers").execute(mapOf("a" to 100, "b" to 200))

        assertThat(result.getOrThrow()).containsEntry("result", 300)
    }
}
