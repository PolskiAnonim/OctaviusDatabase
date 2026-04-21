package org.octavius.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.time.Instant
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.data.DataAccess
import org.octavius.data.builder.*
import org.octavius.data.getOrThrow
import org.octavius.data.type.DISTANT_FUTURE
import org.octavius.data.type.DISTANT_PAST
import org.octavius.database.config.DatabaseConfig
import org.octavius.database.jdbc.DefaultJdbcTransactionProvider
import org.octavius.database.jdbc.JdbcTemplate

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DateTimeInfinityTest {

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

        dataAccess = OctaviusDatabase.fromDataSource(
            dataSource = dataSource,
            packagesToScan = emptyList(),
            dbSchemas = listOf("public"),
            disableFlyway = true,
            disableCoreTypeInitialization = true
        )
        
        val jdbcTemplate = JdbcTemplate(DefaultJdbcTransactionProvider(dataSource))
        jdbcTemplate.execute("DROP TABLE IF EXISTS infinity_test CASCADE;")
        jdbcTemplate.execute("""
            CREATE TABLE infinity_test (
                id SERIAL PRIMARY KEY,
                d DATE,
                ts TIMESTAMP,
                tstz TIMESTAMPTZ
            );
        """.trimIndent())
    }

    @AfterAll
    fun tearDown() {
        dataAccess.close()
    }

    @Test
    fun `should map infinity values correctly for LocalDate`() {
        dataAccess.rawQuery("INSERT INTO infinity_test (id, d) VALUES (1, @plus_infinity), (2, @minus_infinity)").execute("plus_infinity" to LocalDate.DISTANT_FUTURE, "minus_infinity" to LocalDate.DISTANT_PAST).getOrThrow()
        
        val result1 = dataAccess.select("d").from("infinity_test").where("id = 1").toField<LocalDate>().getOrThrow()
        val result2 = dataAccess.select("d").from("infinity_test").where("id = 2").toField<LocalDate>().getOrThrow()
        
        assertEquals(LocalDate.DISTANT_FUTURE, result1)
        assertEquals(LocalDate.DISTANT_PAST, result2)
    }

    @Test
    fun `should map infinity values correctly for LocalDateTime`() {
        dataAccess.rawQuery("INSERT INTO infinity_test (id, ts) VALUES (3, @plus_infinity), (4, @minus_infinity)").execute("plus_infinity" to LocalDateTime.DISTANT_FUTURE, "minus_infinity" to LocalDateTime.DISTANT_PAST).getOrThrow()
        
        val result1 = dataAccess.select("ts").from("infinity_test").where("id = 3").toField<LocalDateTime>().getOrThrow()
        val result2 = dataAccess.select("ts").from("infinity_test").where("id = 4").toField<LocalDateTime>().getOrThrow()
        
        assertEquals(LocalDateTime.DISTANT_FUTURE, result1)
        assertEquals(LocalDateTime.DISTANT_PAST, result2)
    }

    @Test
    fun `should map infinity values correctly for Instant`() {
        dataAccess.rawQuery("INSERT INTO infinity_test (id, tstz) VALUES (5, @plus_infinity), (6, @minus_infinity)").execute("plus_infinity" to Instant.DISTANT_FUTURE, "minus_infinity" to Instant.DISTANT_PAST).getOrThrow()
        
        val result1 = dataAccess.select("tstz").from("infinity_test").where("id = 5").toField<Instant>().getOrThrow()
        val result2 = dataAccess.select("tstz").from("infinity_test").where("id = 6").toField<Instant>().getOrThrow()
        
        assertEquals(Instant.DISTANT_FUTURE, result1, "For infinity")
        assertEquals(Instant.DISTANT_PAST, result2, "For -infinity")
    }
}
