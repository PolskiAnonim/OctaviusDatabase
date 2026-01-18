// Możesz to umieścić w tym samym pakiecie co inne benchmarki
package org.octavius.performance

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.database.RowMappers
import org.octavius.database.config.DatabaseConfig
import org.octavius.database.type.PostgresToKotlinConverter
import org.octavius.database.type.ResultSetValueExtractor
import org.octavius.database.type.registry.TypeRegistry
import org.octavius.database.type.registry.TypeRegistryLoader
import org.postgresql.jdbc.PgResultSetMetaData
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.ResultSet
import kotlin.system.measureTimeMillis

/**
 * Benchmark porównujący wydajność mapowania prostych typów.
 *
 * Porównuje 3 strategie:
 * 1. Raw JDBC - linia bazowa, najszybsza możliwa implementacja.
 * 2. Old Framework (getString) - narzut związany z konwersją wszystkiego przez String.
 * 3. Optimized Framework (Fast Path) - nowa, zoptymalizowana wersja z "szybką ścieżką".
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimpleTypeOverheadBenchmark {

    // --- Konfiguracja ---
    private val TOTAL_ROWS_TO_FETCH = 10000
    private val ITERATIONS = 20
    private val WARMUP_ITERATIONS = 10

    // --- Wyniki ---
    private val rawJdbcTimings = mutableListOf<Long>()
    private val oldFrameworkTimings = mutableListOf<Long>()
    private val optimizedFrameworkTimings = mutableListOf<Long>() // NOWA LISTA

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var typesConverter: PostgresToKotlinConverter
    private lateinit var typeRegistry: TypeRegistry
    private lateinit var valueExtractor: ResultSetValueExtractor // NOWY EKSTRAKTOR

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
        val hikariDataSource = HikariDataSource().apply {
            jdbcUrl = databaseConfig.dbUrl
            username = databaseConfig.dbUsername
            password = databaseConfig.dbPassword
            maximumPoolSize = 5
        }

        jdbcTemplate = JdbcTemplate(hikariDataSource)

        typeRegistry =
            TypeRegistryLoader(
                jdbcTemplate,
                databaseConfig.packagesToScan.filter { it != "org.octavius.domain.test.dynamic" && it != "org.octavius.domain.test.existing" },
                databaseConfig.dbSchemas
            ).load()
        typesConverter = PostgresToKotlinConverter(typeRegistry)
        // --- NOWOŚĆ: Inicjalizujemy ekstraktor ---
        valueExtractor = ResultSetValueExtractor(typeRegistry)


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
        val oldFrameworkMapper = OldFrameworkRowMapper(typesConverter)
        val optimizedFrameworkMapper = RowMappers(valueExtractor).ColumnNameMapper() // NOWY MAPPER

        // --- WARM-UP ---
        println("\n--- ROZGRZEWKA (x$WARMUP_ITERATIONS iteracji, wyniki ignorowane) ---")
        repeat(WARMUP_ITERATIONS) {
            jdbcTemplate.query(sql, rawMapper)
            jdbcTemplate.query(sql, oldFrameworkMapper)
            jdbcTemplate.query(sql, optimizedFrameworkMapper) // Rozgrzewamy też nowy
        }
        println("--- ROZGRZEWKA ZAKOŃCZONA ---\n")

        // --- POMIAR ---
        println("--- POMIAR (x$ITERATIONS iteracji dla $TOTAL_ROWS_TO_FETCH wierszy) ---")
        repeat(ITERATIONS) { i ->
            print("Iteracja ${i + 1}/$ITERATIONS...\r")

            // Mierz Raw JDBC
            rawJdbcTimings.add(measureTimeMillis { jdbcTemplate.query(sql, rawMapper) })

            // Mierz Stary Framework
            oldFrameworkTimings.add(measureTimeMillis { jdbcTemplate.query(sql, oldFrameworkMapper) })

            // Mierz Nowy, Zoptymalizowany Framework
            optimizedFrameworkTimings.add(measureTimeMillis { jdbcTemplate.query(sql, optimizedFrameworkMapper) })
        }
        println("\n--- POMIAR ZAKOŃCZONY ---\n")
    }

    @AfterAll
    fun printResults() {
        val avgRaw = rawJdbcTimings.average()
        val avgOld = oldFrameworkTimings.average()
        val avgOptimized = optimizedFrameworkTimings.average()

        val overheadOldMs = avgOld - avgRaw
        val overheadOldPercent = (overheadOldMs / avgRaw) * 100

        val overheadOptimizedMs = avgOptimized - avgRaw
        val overheadOptimizedPercent = (overheadOptimizedMs / avgRaw) * 100

        println("\n--- OSTATECZNE WYNIKI PORÓWNANIA (średnia z $ITERATIONS iteracji) ---")
        println("==================================================================================")
        println("  Pobieranie i mapowanie $TOTAL_ROWS_TO_FETCH wierszy:")
        println("----------------------------------------------------------------------------------")
        println("  1. Raw JDBC (linia bazowa):      ${String.format("%7.2f", avgRaw)} ms")
        println("  2. Stary Framework (getString):    ${String.format("%7.2f", avgOld)} ms")
        println("  3. Nowy Framework (Optimized):   ${String.format("%7.2f", avgOptimized)} ms")
        println("----------------------------------------------------------------------------------")
        println(
            "  Narzut Starego Frameworka:   +${String.format("%.2f", overheadOldMs)} ms (+${
                String.format(
                    "%.1f",
                    overheadOldPercent
                )
            }%)"
        )
        println(
            "  Narzut Nowego Frameworka:    +${
                String.format(
                    "%.2f",
                    overheadOptimizedMs
                )
            } ms (+${String.format("%.1f", overheadOptimizedPercent)}%)"
        )
        println("==================================================================================")
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
        data["ts_val"] = rs.getTimestamp(5)?.toLocalDateTime()
        data["bool_val"] = rs.getBoolean(6)
        data["numeric_val"] = rs.getBigDecimal(7)
        return data
    }
}

/**
 * Mapper implementujący starą strategię frameworka (wszystko przez getString).
 */
private class OldFrameworkRowMapper(private val converter: PostgresToKotlinConverter) : RowMapper<Map<String, Any?>> {
    override fun mapRow(rs: ResultSet, rowNum: Int): Map<String, Any?> {
        val data = mutableMapOf<String, Any?>()
        val metaData = rs.metaData as PgResultSetMetaData
        for (i in 1..metaData.columnCount) {
            val columnName = metaData.getColumnName(i)
            val columnType = metaData.getColumnTypeName(i)
            val rawValue = rs.getString(i)
            data[columnName] = converter.convert(rawValue, columnType)
        }
        return data
    }
}
