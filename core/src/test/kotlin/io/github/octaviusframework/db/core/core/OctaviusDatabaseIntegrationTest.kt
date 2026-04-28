package io.github.octaviusframework.db.core.core

import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.db.core.DatabaseAccess
import io.github.octaviusframework.db.core.OctaviusDatabase
import io.github.octaviusframework.db.core.config.DatabaseConfig
import io.github.octaviusframework.db.core.jdbc.JdbcTemplate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OctaviusDatabaseIntegrationTest {

    @Test
    fun `should apply hikari properties and close pool correctly`() {
        val config = DatabaseConfig.loadFromFile("test-database.properties").copy(
            hikariProperties = mapOf(
                "maximumPoolSize" to "12",
                "poolName" to "IntegrationTestPool"
            )
        )

        val da = OctaviusDatabase.fromConfig(config)

        try {
            // Access internal datasource using reflection or by casting if we know it is DatabaseAccess
            // DatabaseAccess is internal, so we can cast it here since we are in the same module
            val databaseAccess = da as DatabaseAccess

            // Get datasource from JdbcTemplate
            val dataSource = databaseAccess.javaClass.getDeclaredField("jdbcTemplate").let { field ->
                field.isAccessible = true
                val jdbcTemplate = field.get(databaseAccess) as JdbcTemplate
                jdbcTemplate.dataSource as HikariDataSource
            }

            assertEquals(12, dataSource.maximumPoolSize)
            assertEquals("IntegrationTestPool", dataSource.poolName)
            assertFalse(dataSource.isClosed)

            // Close DataAccess
            da.close()

            // Verify Hikari pool is closed
            assertTrue(dataSource.isClosed)

        } finally {
            // Ensure cleanup even if fail
            da.close()
        }
    }
}