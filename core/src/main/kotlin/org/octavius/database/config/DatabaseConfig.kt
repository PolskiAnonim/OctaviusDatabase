package org.octavius.database.config

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.*

/**
 * Immutable configuration for PostgreSQL database connection.
 *
 * Stores connection parameters and configuration for system components.
 *
 * @property dbUrl JDBC connection URL.
 * @property dbUsername Database user name.
 * @property dbPassword Database user password.
 * @property dbSchemas List of schemas to be handled.
 * @property setSearchPath Whether HikariCP should set `search_path` on connection initialization to all schemas.
 * @property packagesToScan List of packages to scan by ClassGraph for type annotations.
 * @property dynamicDtoStrategy Strategy for serializing classes as DynamicDto.
 * @property flywayBaselineVersion If not null, indicates which version Flyway should treat the existing schema as.
 * @property disableFlyway Disable automatic Flyway migrations.
 * @property disableCoreTypeInitialization Disable automatic creation of framework types (e.g., `dynamic_dto`).
 *           Use when the application lacks DDL privileges and schema is managed externally.
 */
data class DatabaseConfig(
    val dbUrl: String,
    val dbUsername: String,
    val dbPassword: String,
    val dbSchemas: List<String>,
    val setSearchPath: Boolean,
    val packagesToScan: List<String>,
    val dynamicDtoStrategy: DynamicDtoSerializationStrategy = DynamicDtoSerializationStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS,
    val flywayBaselineVersion: String? = null,
    val disableFlyway: Boolean = false,
    val disableCoreTypeInitialization: Boolean = false,
) {
    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * Loads configuration from a `properties` file and creates a `DatabaseConfig` instance.
         *
         * @param fileName Name of the file in resources (e.g., "database.properties").
         * @return Created, immutable [DatabaseConfig] instance.
         * @throws IllegalArgumentException if the configuration file is not found
         *         or a required key is missing.
         * @throws java.io.IOException if an error occurs while reading the file.
         */
        fun loadFromFile(fileName: String): DatabaseConfig {
            logger.info { "Loading database configuration from file: $fileName" }
            val props = Properties()

            val resourceStream = this::class.java.classLoader.getResourceAsStream(fileName)
                ?: throw IllegalArgumentException("Could not find properties file '$fileName' in resources.")

            resourceStream.use { props.load(it) }

            // --- Basic database configuration ---
            val url = props.getProperty("db.url")
                ?: throw IllegalArgumentException("Missing required property 'db.url' in '$fileName'")
            val username = props.getProperty("db.username")
                ?: throw IllegalArgumentException("Missing required property 'db.username' in '$fileName'")
            val password = props.getProperty("db.password")
                ?: throw IllegalArgumentException("Missing required property 'db.password' in '$fileName'")
            val schemasString = props.getProperty("db.schemas")
                ?: throw IllegalArgumentException("Missing required property 'db.schemas' in '$fileName'")
            val schemas = schemasString.split(",").map { it.trim() }.filter { it.isNotEmpty() }

            // --- Application-specific configuration ---
            val setSearchPath = props.getProperty("db.setSearchPath", "true").toBoolean()

            val packagesString = props.getProperty("db.packagesToScan")
                ?: throw IllegalArgumentException("Missing required property 'db.packagesToScan' in '$fileName'")
            val packages = packagesString.split(",").map { it.trim() }.filter { it.isNotEmpty() }

            val dynamicDtoStrategyString: String? = props.getProperty("db.dynamicDtoStrategy")
            val dynamicDtoStrategy =
                dynamicDtoStrategyString?.let { DynamicDtoSerializationStrategy.valueOf(dynamicDtoStrategyString) }
                    ?: DynamicDtoSerializationStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS

            val flywayBaselineVersion: String? = props.getProperty("db.flywayBaselineVersion")
            val disableFlyway: Boolean = props.getProperty("db.disableFlyway").toBoolean()
            val disableCoreTypeInitialization: Boolean = props.getProperty("db.disableCoreTypeInitialization").toBoolean()

            logger.info { "Database configuration loaded successfully" }
            logger.debug { "Database URL: '$url'" }
            logger.debug { "Database schemas: ${schemas.joinToString()}" }
            logger.debug { "Set search_path on connect: $setSearchPath" }
            logger.debug { "Packages to scan for types: ${packages.joinToString()}" }
            logger.debug { "DynamicDTO strategy: $dynamicDtoStrategy" }
            logger.debug { "Flyway baseline version: $flywayBaselineVersion" }
            logger.debug { "Disable flyway migrations: $disableFlyway" }
            logger.debug { "Disable core type initialization: $disableCoreTypeInitialization" }

            return DatabaseConfig(
                dbUrl = url,
                dbUsername = username,
                dbPassword = password,
                dbSchemas = schemas,
                setSearchPath = setSearchPath,
                packagesToScan = packages,
                dynamicDtoStrategy,
                flywayBaselineVersion,
                disableFlyway,
                disableCoreTypeInitialization
            )
        }
    }
}
