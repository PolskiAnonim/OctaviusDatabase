package org.octavius.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.octavius.data.DataAccess
import org.octavius.data.exception.InitializationException
import org.octavius.data.exception.InitializationExceptionMessage
import org.octavius.data.exception.QueryContext
import org.octavius.data.type.QualifiedName
import org.octavius.database.OctaviusDatabase.fromDataSource
import org.octavius.database.config.AppInfo
import org.octavius.database.config.DatabaseConfig
import org.octavius.database.config.DynamicDtoSerializationStrategy
import org.octavius.database.jdbc.DefaultJdbcTransactionProvider
import org.octavius.database.jdbc.JdbcTemplate
import org.octavius.database.jdbc.JdbcTransactionProvider
import org.octavius.database.type.KotlinToPostgresConverter
import org.octavius.database.type.registry.TypeRegistry
import org.octavius.database.type.registry.TypeRegistryLoader
import java.sql.Connection
import java.sql.DriverManager
import java.util.*
import javax.sql.DataSource
import kotlin.time.measureTime

/**
 * Central management and entry point for the Octavius Database framework.
 *
 * This object is responsible for orchestrating the initialization of the data access layer,
 * including connection pooling, database migrations, type discovery, and framework-level
 * infrastructure.
 *
 * Primary responsibilities:
 * - **DataSource Configuration:** Setting up HikariCP with PostgreSQL-optimized defaults.
 * - **Schema Management:** Coordinating optional database migrations via `migrationRunner`.
 * - **Type Discovery:** Coordinating [TypeRegistryLoader] to scan classpath and database for custom types (ENUMs, COMPOSITEs).
 * - **Infrastructure Initialization:** Ensuring required PostgreSQL functions (e.g., for Dynamic DTOs) are present.
 */
object OctaviusDatabase {
    private val logger = KotlinLogging.logger {}

    /**
     * Initializes [DataAccess] from a structured [DatabaseConfig].
     *
     * This is the preferred method for standard applications. It handles:
     * 1. Building a [HikariDataSource] with provided credentials and pool settings.
     * 2. Setting `search_path` automatically if configured.
     * 3. Delegating to [fromDataSource] for the rest of the initialization.
     *
     * @param config The framework configuration object.
     * @param transactionProvider Optional custom transaction manager.
     * @return A fully initialized, thread-safe [DataAccess] instance.
     * @throws InitializationException if connection fails or migrations cannot be applied.
     */
    fun fromConfig(config: DatabaseConfig, transactionProvider: JdbcTransactionProvider? = null, migrationRunner: ((DataSource) -> Unit)? = null): DataAccess {
        logger.info { "Initializing DataSource..." }
        // 1. Configuration-dependent setting of `connectionInitSql`
        val connectionInitSql = if (config.setSearchPath && config.dbSchemas.isNotEmpty()) {
            val schemas = config.dbSchemas.joinToString(", ") { QualifiedName.quoteIdentifier(it) }
            logger.debug { "Setting connectionInitSql to 'SET search_path TO $schemas'" }
            "SET search_path TO $schemas"
        } else {
            logger.debug { "connectionInitSql will not be set." }
            null
        }

        logger.debug { "Configuring HikariCP datasource with URL: ${config.dbUrl}" }

        val props = Properties().apply {
            setProperty("jdbcUrl", config.dbUrl)
            setProperty("username", config.dbUsername)
            setProperty("password", config.dbPassword)
            setProperty("maximumPoolSize", "10")

            config.hikariProperties.forEach { (key, value) ->
                setProperty(key, value)
            }
        }

        val hikariConfig = HikariConfig(props).apply {
            if (connectionInitSql != null) {
                this.connectionInitSql = connectionInitSql
            }
        }

        val dataSource = try {
            HikariDataSource(hikariConfig)
        } catch (e: Exception) {
            throw InitializationException(
                InitializationExceptionMessage.CONNECTION_FAILED,
                details = "Failed to initialize HikariCP connection pool.",
                cause = e,
                queryContext = QueryContext(sql = "N/A", mapOf())
            )
        }
        logger.debug { "HikariCP datasource initialized with pool size: ${hikariConfig.maximumPoolSize}" }

        return fromDataSource(
            dataSource = dataSource,
            packagesToScan = config.packagesToScan,
            dbSchemas = config.dbSchemas,
            dynamicDtoStrategy = config.dynamicDtoStrategy,
            disableCoreTypeInitialization = config.disableCoreTypeInitialization,
            showBanner = config.showBanner,
            transactionProvider = transactionProvider,
            migrationRunner = migrationRunner,
            onClose = {
                logger.info { "Closing internal HikariDataSource..." }
                dataSource.close()
            }
        )
    }

    /**
     * Initializes [DataAccess] using an existing [DataSource].
     *
     * This method provides fine-grained control over the initialization process and is suitable 
     * for environments where the [DataSource] is managed externally (e.g., by a DI container 
     * like Spring or a JavaEE application server).
     *
     * The initialization sequence includes:
     * 1. **Core Type Initialization:** Ensures framework-specific types (like `dynamic_dto`) exist.
     * 2. **Database Migrations:** Runs optional user-defined migrations via [migrationRunner].
     * 3. **Type Registry Loading:** Scans the database and classpath to build a type metadata map.
     * 4. **Converter Setup:** Initializes bidirectional Kotlin-to-PostgreSQL mapping logic.
     *
     * @param dataSource The pre-configured [DataSource] to use for all database operations.
     * @param packagesToScan List of package names to scan for annotated classes (@PgEnum, @PgComposite, etc.).
     * @param dbSchemas List of database schemas to scan for type definitions.
     * @param dynamicDtoStrategy Strategy for handling Dynamic DTO serialization. Defaults to [DynamicDtoSerializationStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS].
     * @param disableCoreTypeInitialization If true, skip ensuring framework-specific types exist.
     * @param showBanner If true, prints the Octavius banner to standard output upon successful initialization.
     * @param transactionProvider Optional custom transaction manager. Defaults to [DefaultJdbcTransactionProvider].
     * @param listenerConnectionFactory Optional factory for creating dedicated connections for LISTEN/NOTIFY. 
     *                                  If null, a default strategy is used (direct DriverManager for Hikari, otherwise directly from DataSource).
     * @param onClose Optional callback executed when the returned [DataAccess] is closed.
     * @return A fully initialized [DataAccess] instance.
     */
    fun fromDataSource(
        dataSource: DataSource,
        packagesToScan: List<String>,
        dbSchemas: List<String>,
        dynamicDtoStrategy: DynamicDtoSerializationStrategy = DynamicDtoSerializationStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS,
        disableCoreTypeInitialization: Boolean = false,
        showBanner: Boolean = true,
        transactionProvider: JdbcTransactionProvider? = null,
        listenerConnectionFactory: (() -> Connection)? = null,
        migrationRunner: ((DataSource) -> Unit)? = null,
        onClose: (() -> Unit)? = null
    ): DataAccess {
        logger.info { "Initializing OctaviusDatabase..." }
        val transactionManager = transactionProvider ?: DefaultJdbcTransactionProvider(dataSource)
        val jdbcTemplate = JdbcTemplate(transactionManager)

        // 1. Framework Infrastructure (Idempotent)
        if (!disableCoreTypeInitialization) {
            CoreTypeInitializer.ensureRequiredTypes(jdbcTemplate)
        } else {
            logger.info { "Core type initialization disabled - assuming types already exist in database." }
        }

        // 2. User Migrations
        migrationRunner?.invoke(dataSource)

        logger.debug { "Loading type registry..." }
        val typeRegistry: TypeRegistry
        val typeRegistryLoadTime = measureTime {
            val loader = TypeRegistryLoader(jdbcTemplate, packagesToScan, dbSchemas)
            typeRegistry = loader.load()
        }
        logger.debug { "Type registry loaded successfully in ${typeRegistryLoadTime.inWholeMilliseconds}ms" }

        logger.debug { "Initializing converters" }
        val kotlinToPostgresConverter = KotlinToPostgresConverter(typeRegistry, dynamicDtoStrategy)
        val resolvedListenerConnectionFactory = listenerConnectionFactory ?: resolveListenerConnectionFactory(dataSource)

        if (showBanner) {
            printBanner()
        }

        return DatabaseAccess(
            jdbcTemplate,
            transactionManager,
            typeRegistry,
            kotlinToPostgresConverter,
            resolvedListenerConnectionFactory,
            onClose
        )
    }

    private fun resolveListenerConnectionFactory(dataSource: DataSource): () -> Connection {
        if (dataSource is HikariDataSource) {
            logger.debug { "LISTEN connections will use DriverManager (bypassing HikariCP pool)" }
            return { DriverManager.getConnection(dataSource.jdbcUrl, dataSource.username, dataSource.password) }
        }
        logger.warn {
            "Cannot determine raw JDBC URL from the provided DataSource (${dataSource::class.simpleName}). " +
            "LISTEN connections will use DataSource directly. " +
            "Pass a custom listenerConnectionFactory to fromDataSource() to avoid this."
        }
        return { dataSource.connection }
    }

    private fun printBanner() {
        val banner = """
           ____   _____ _______  __      _______ _    _  _____ 
          / __ \ / ____|__   __|/\ \    / /_   _| |  | |/ ____|
         | |  | | |       | |  /  \ \  / /  | | | |  | | (___  
         | |  | | |       | | / /\ \ \/ /   | | | |  | |\___ \ 
         | |__| | |____   | |/ ____ \  /   _| |_| |__| |____) |
          \____/ \_____|  |_/_/    \_\/   |_____|\____/|_____/ 
        --------------------------------------------------------
         OCTAVIUS DATABASE :: ROME v${AppInfo.VERSION}
        --------------------------------------------------------
        """.trimIndent()
        println(banner)
    }
}
