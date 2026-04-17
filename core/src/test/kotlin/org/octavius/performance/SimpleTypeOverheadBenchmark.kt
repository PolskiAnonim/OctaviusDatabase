// Możesz to umieścić w tym samym pakiecie co inne benchmarki
package org.octavius.performance

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.*
import org.octavius.database.config.DatabaseConfig
import org.octavius.database.jdbc.JdbcTemplate
import org.octavius.database.jdbc.RowMapper
import org.octavius.database.jdbc.RowMappers
import org.octavius.database.jdbc.SpringJdbcTransactionProvider
import org.octavius.database.type.PositionalQuery
import org.octavius.database.type.PostgresToKotlinConverter
import org.octavius.database.type.registry.TypeRegistry
import org.octavius.database.type.registry.TypeRegistryLoader
import org.postgresql.jdbc.PgResultSet
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.ResultSet
import kotlin.system.measureTimeMillis

/**
 * Benchmark porównujący wydajność mapowania prostych typów.
 *
 * Porównuje 2 strategie:
 * 1. Raw JDBC - linia bazowa, najszybsza możliwa implementacja.
 * 2. Framework (Fast Path)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Disabled
class SimpleTypeOverheadBenchmark {

    // --- Konfiguracja ---
    private val TOTAL_ROWS_TO_FETCH = 10000
    private val ITERATIONS = 20
    private val WARMUP_ITERATIONS = 10

    // --- Wyniki ---
    private val rawJdbcTimings = mutableListOf<Long>()
    private val optimizedFrameworkTimings = mutableListOf<Long>() // NOWA LISTA

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var typesConverter: PostgresToKotlinConverter
    private lateinit var typeRegistry: TypeRegistry

    @BeforeAll
    fun setup() {
        println("--- KONFIGURACJA BENCHMARKU NARZUTU DLA PROSTYCH TYPÓW ---")
        val databaseConfig = DatabaseConfig.loadFromFile("test-database.properties")
        val connectionUrl = databaseConfig.dbUrl
        val dbName = connectionUrl.substringAfterLast("/")
        if (!connectionUrl.contains("localhost:5432") || dbName != "octavius_test") {
            throw IllegalStateException(
                "ABORTING TEST! Attempting to run destructive tests on a non-test database. " +
                        "Connection URL: '$connectionUrl'. This is a safety guard to prevent data loss."
            )
        }
        println("Safety guard passed. Connected to the correct test database: $dbName")
        dataSource = HikariDataSource().apply {
            jdbcUrl = databaseConfig.dbUrl
            username = databaseConfig.dbUsername
            password = databaseConfig.dbPassword
            maximumPoolSize = 5
        }

        jdbcTemplate = JdbcTemplate(SpringJdbcTransactionProvider(dataSource))

        typeRegistry =
            TypeRegistryLoader(
                jdbcTemplate,
                databaseConfig.packagesToScan.filter { it != "org.octavius.domain.test.dynamic" && it != "org.octavius.domain.test.existing" },
                databaseConfig.dbSchemas
            ).load()
        typesConverter = PostgresToKotlinConverter(typeRegistry)


        try {
            val initSql = String(
                Files.readAllBytes(
                    Paths.get(
                        this::class.java.classLoader.getResource("init-simple-test-db.sql")!!.toURI()
                    )
                )
            )
            jdbcTemplate.execute(initSql)
            println("Simple test DB schema and data initialized successfully.")
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    @Test
    fun `run full benchmark comparison`() {
        val sql = "SELECT * FROM simple_type_benchmark LIMIT $TOTAL_ROWS_TO_FETCH"
        val rawMapper = RawJdbcRowMapper()
        val frameworkMapper = RowMappers(typeRegistry).ColumnNameMapper() // NOWY MAPPER

        // --- WARM-UP ---
        println("\n--- ROZGRZEWKA (x$WARMUP_ITERATIONS iteracji, wyniki ignorowane) ---")
        repeat(WARMUP_ITERATIONS) {
            jdbcTemplate.query(PositionalQuery(sql, listOf()), rawMapper)
            jdbcTemplate.query(PositionalQuery(sql, listOf()), frameworkMapper) // Rozgrzewamy też nowy
        }
        println("--- ROZGRZEWKA ZAKOŃCZONA ---\n")

        // --- POMIAR ---
        println("--- POMIAR (x$ITERATIONS iteracji dla $TOTAL_ROWS_TO_FETCH wierszy) ---")
        repeat(ITERATIONS) { i ->
            print("Iteracja ${i + 1}/$ITERATIONS...\r")

            // Mierz Raw JDBC
            rawJdbcTimings.add(measureTimeMillis { jdbcTemplate.query(PositionalQuery(sql, listOf()), rawMapper) })

            // Mierz Framework
            optimizedFrameworkTimings.add(measureTimeMillis { jdbcTemplate.query(PositionalQuery(sql, listOf()), frameworkMapper) })
        }
        println("\n--- POMIAR ZAKOŃCZONY ---\n")
    }

    @AfterAll
    fun printResults() {
        val avgRaw = rawJdbcTimings.average()
        val avgOptimized = optimizedFrameworkTimings.average()

        val overheadFrameworkMs = avgOptimized - avgRaw
        val overheadFrameworkPercent = (overheadFrameworkMs / avgRaw) * 100

        println("\n--- OSTATECZNE WYNIKI PORÓWNANIA (średnia z $ITERATIONS iteracji) ---")
        println("==================================================================================")
        println("  Pobieranie i mapowanie $TOTAL_ROWS_TO_FETCH wierszy:")
        println("----------------------------------------------------------------------------------")
        println("  1. Raw JDBC (linia bazowa):      ${String.format("%7.2f", avgRaw)} ms")
        println("  2. Framework:                    ${String.format("%7.2f", avgOptimized)} ms")
        println("----------------------------------------------------------------------------------")
        println(
            "  Narzut Frameworka:    +${
                String.format(
                    "%.2f",
                    overheadFrameworkMs
                )
            } ms (+${String.format("%.1f", overheadFrameworkPercent)}%)"
        )
        println("==================================================================================")

    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }
}
// --- Implementacje Mapperów i klas pomocniczych ---

/**
 * Linia bazowa - najszybszy możliwy kod.
 */
private class RawJdbcRowMapper : RowMapper<Map<String, Any?>> {
    override fun mapRow(rs: ResultSet, rowNum: Int): Map<String, Any?> {
        val data = mutableMapOf<String, Any?>()
        data["id"] = rs.getInt(1)
        data["int_val"] = rs.getInt(2)
        data["long_val"] = rs.getLong(3)
        data["text_val"] = rs.getString(4)
        data["ts_val"] = rs.getObject(5, java.time.LocalDateTime::class.java)
        data["bool_val"] = rs.getBoolean(6)
        data["numeric_val"] = rs.getBigDecimal(7)
        return data
    }
}
