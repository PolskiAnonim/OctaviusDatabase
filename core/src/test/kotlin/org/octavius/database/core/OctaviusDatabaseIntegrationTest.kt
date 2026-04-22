package org.octavius.database.core

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.octavius.database.DatabaseAccess
import org.octavius.database.OctaviusDatabase
import org.octavius.database.config.DatabaseConfig
import org.octavius.database.jdbc.JdbcTemplate

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

            Assertions.assertEquals(12, dataSource.maximumPoolSize)
            Assertions.assertEquals("IntegrationTestPool", dataSource.poolName)
            Assertions.assertFalse(dataSource.isClosed)

            // Close DataAccess
            da.close()

            // Verify Hikari pool is closed
            Assertions.assertTrue(dataSource.isClosed)

        } finally {
            // Ensure cleanup even if assertions fail
            da.close()
        }
    }
}