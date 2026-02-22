package org.octavius.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.flywaydb.core.Flyway
import org.octavius.data.DataAccess
import org.octavius.database.config.DatabaseConfig
import org.octavius.database.config.DynamicDtoSerializationStrategy
import org.octavius.database.type.KotlinToPostgresConverter
import org.octavius.database.type.registry.TypeRegistry
import org.octavius.database.type.registry.TypeRegistryLoader
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource
import kotlin.time.measureTime

/**
 * Database management system - central access point to database services.
 *
 * Initializes all necessary components for database work.
 *
 * Responsible for:
 * - HikariCP connection pool configuration with PostgreSQL (for fromConfig method)
 * - Spring transaction manager initialization
 * - Automatic type registry loading from database and classpath
 * - Providing data access services through [DataAccess] interface
 *
 */
object OctaviusDatabase {
    private val logger = KotlinLogging.logger {}

    fun fromConfig(config: DatabaseConfig): DataAccess {
        logger.info { "Initializing DataSource..." }
        // 1. Configuration-dependent setting of `connectionInitSql`
        val connectionInitSql = if (config.setSearchPath && config.dbSchemas.isNotEmpty()) {
            val schemas = config.dbSchemas.joinToString(", ")
            logger.debug { "Setting connectionInitSql to 'SET search_path TO $schemas'" }
            "SET search_path TO $schemas"
        } else {
            logger.debug { "connectionInitSql will not be set." }
            null
        }

        logger.debug { "Configuring HikariCP datasource with URL: ${config.dbUrl}" }
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.dbUrl
            username = config.dbUsername
            password = config.dbPassword
            maximumPoolSize = 10
            this.connectionInitSql = connectionInitSql
        }
        val dataSource = HikariDataSource(hikariConfig)
        logger.debug { "HikariCP datasource initialized with pool size: ${hikariConfig.maximumPoolSize}" }

        val searchPathSql = connectionInitSql
        val listenerConnectionFactory: () -> Connection = {
            DriverManager.getConnection(config.dbUrl, config.dbUsername, config.dbPassword).also { conn ->
                searchPathSql?.let { sql -> conn.createStatement().use { it.execute(sql) } }
            }
        }

        return fromDataSource(
            dataSource = dataSource,
            packagesToScan = config.packagesToScan,
            dbSchemas = config.dbSchemas,
            dynamicDtoStrategy = config.dynamicDtoStrategy,
            flywayBaselineVersion = config.flywayBaselineVersion,
            disableFlyway = config.disableFlyway,
            disableCoreTypeInitialization = config.disableCoreTypeInitialization,
            listenerConnectionFactory = listenerConnectionFactory
        )
    }

    fun fromDataSource(
        dataSource: DataSource,
        packagesToScan: List<String>,
        dbSchemas: List<String>,
        dynamicDtoStrategy: DynamicDtoSerializationStrategy = DynamicDtoSerializationStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS,
        flywayBaselineVersion: String? = null,
        disableFlyway: Boolean = false,
        disableCoreTypeInitialization: Boolean = false,
        listenerConnectionFactory: (() -> Connection)? = null
    ): DataAccess {
        logger.info { "Initializing OctaviusDatabase..." }
        val jdbcTemplate = JdbcTemplate(dataSource)
        val transactionManager = JdbcTransactionManager(dataSource)

        // Initialize Framework Internals (Idempotent)
        if (!disableCoreTypeInitialization) {
            CoreTypeInitializer.ensureRequiredTypes(jdbcTemplate)
        } else {
            logger.info { "Core type initialization disabled - assuming dynamic_dto type exists in the database" }
        }

        // Run User Migrations (Flyway)
        if (!disableFlyway) runMigrations(dataSource, dbSchemas, flywayBaselineVersion)

        logger.debug { "Loading type registry from database..." }
        val typeRegistry: TypeRegistry
        val typeRegistryLoadTime = measureTime {
            val loader = TypeRegistryLoader(
                jdbcTemplate,
                packagesToScan,
                dbSchemas
            )
            typeRegistry = loader.load()
        }
        logger.debug { "Type registry loaded successfully in ${typeRegistryLoadTime.inWholeMilliseconds}ms" }

        logger.debug { "Initializing converters" }
        val kotlinToPostgresConverter = KotlinToPostgresConverter(typeRegistry, dynamicDtoStrategy)

        val resolvedListenerConnectionFactory: () -> Connection = listenerConnectionFactory
            ?: resolveListenerConnectionFactory(dataSource)

        logger.info { "OctaviusDatabase initialization completed" }
        return DatabaseAccess(
            jdbcTemplate,
            transactionManager,
            typeRegistry,
            kotlinToPostgresConverter,
            resolvedListenerConnectionFactory
        )
    }

    private fun resolveListenerConnectionFactory(dataSource: DataSource): () -> Connection {
        if (dataSource is HikariDataSource) {
            logger.debug { "LISTEN connections will use DriverManager (bypassing HikariCP pool)" }
            return { DriverManager.getConnection(dataSource.jdbcUrl, dataSource.username, dataSource.password) }
        }
        logger.warn {
            "Cannot determine raw JDBC URL from the provided DataSource (${dataSource::class.simpleName}). " +
            "LISTEN connections will use the main connection pool. " +
            "Pass a custom listenerConnectionFactory to fromDataSource() to avoid this."
        }
        return { dataSource.connection }
    }

    private fun runMigrations(dataSource: DataSource, schemas: List<String>, flywayBaselineVersion: String?) {
        logger.info { "Checking database migrations..." }

        // Flyway configuration
        val flywayConfig = Flyway.configure()
            .dataSource(dataSource)
            .schemas(*schemas.toTypedArray())
            // Default location is classpath:db/migration
            .locations("classpath:db/migration")
            .createSchemas(true)

        if (flywayBaselineVersion != null) {
            flywayConfig
                .baselineOnMigrate(true)
                .baselineVersion(flywayBaselineVersion)
        }

        val flyway = flywayConfig.load()

        try {
            val result = flyway.migrate()
            if (result.migrationsExecuted > 0) {
                logger.info { "Successfully applied ${result.migrationsExecuted} migrations." }
            } else {
                logger.debug { "Database is up to date." }
            }
        } catch (e: Exception) {
            logger.error(e) { "Migration failed!" }
            throw e
        }
    }

}
