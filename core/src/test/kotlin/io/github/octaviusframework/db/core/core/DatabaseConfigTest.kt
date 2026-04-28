package io.github.octaviusframework.db.core.core

import io.github.octaviusframework.db.core.config.DatabaseConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.*

class DatabaseConfigTest {

    @Test
    fun `should correctly map properties to DatabaseConfig`() {
        val props = Properties().apply {
            setProperty("db.url", "jdbc:postgresql://localhost:5432/test")
            setProperty("db.username", "user")
            setProperty("db.password", "pass")
            setProperty("db.schemas", "public, private")
            setProperty("db.packagesToScan", "io.github.octaviusframework.db.api")
            setProperty("db.hikari.maximumPoolSize", "20")
            setProperty("db.hikari.leakDetectionThreshold", "3000")
        }

        val config = DatabaseConfig.fromProperties(props)

        assertEquals("jdbc:postgresql://localhost:5432/test", config.dbUrl)
        assertEquals("user", config.dbUsername)
        assertEquals("pass", config.dbPassword)
        assertEquals(listOf("public", "private"), config.dbSchemas)
        assertEquals(listOf("io.github.octaviusframework.db.api"), config.packagesToScan)

        // Hikari properties
        assertEquals(2, config.hikariProperties.size)
        assertEquals("20", config.hikariProperties["maximumPoolSize"])
        assertEquals("3000", config.hikariProperties["leakDetectionThreshold"])
        assertEquals(true, config.showBanner) // default
    }

    @Test
    fun `should correctly map showBanner property`() {
        val props = Properties().apply {
            setProperty("db.url", "jdbc:postgresql://localhost:5432/test")
            setProperty("db.username", "user")
            setProperty("db.password", "pass")
            setProperty("db.schemas", "public")
            setProperty("db.packagesToScan", "io.github.octaviusframework.db.api")
            setProperty("db.showBanner", "false")
        }

        val config = DatabaseConfig.fromProperties(props)
        assertEquals(false, config.showBanner)
    }
}