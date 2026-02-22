package org.octavius.performance

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.octavius.data.DataAccess
import org.octavius.data.annotation.PgComposite
import org.octavius.database.OctaviusDatabase
import org.octavius.database.config.DatabaseConfig
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import kotlin.system.measureTimeMillis

// Prosta data class do przechowywania naszych danych testowych
@PgComposite(name = "performance_test")
data class PerformanceTestData(val val1: Int, val val2: String)

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ComprehensiveBulkInsertBenchmark {

    // --- Konfiguracja Benchmarku ---
    private val ITERATIONS_PER_SIZE = 10

    // --- Zmienne przechowujące wyniki ---
    private val allJdbcResults = ConcurrentHashMap<Int, MutableList<Long>>()
    private val allUnnestRowResults = ConcurrentHashMap<Int, MutableList<Long>>()
    private val allUnnestParallelResults = ConcurrentHashMap<Int, MutableList<Long>>()
    private val allUnnestTypedArrayResults = ConcurrentHashMap<Int, MutableList<Long>>()

    // --- Zmienne konfiguracyjne ---
    private lateinit var dataSource: DataSource
    private lateinit var dataAccess: DataAccess

    companion object {
        @JvmStatic
        fun rowCountsProvider(): List<Int> = listOf(10, 20, 50, 100, 200, 500, 1000, 5000, 10000, 15000, 20000)
    }

    @BeforeAll
    fun setup() {
        println("--- ROZPOCZYNANIE KONFIGURACJI BENCHMARKU ---")

        // --- Krok 1: Bezpieczna konfiguracja połączenia ---
        val databaseConfig = DatabaseConfig.loadFromFile("test-database.properties")
        val connectionUrl = databaseConfig.dbUrl
        val dbName = connectionUrl.substringAfterLast("/")
        if (!connectionUrl.contains("localhost:5432") || dbName != "octavius_test") {
            throw IllegalStateException("ABORTING TEST! Safety guard failed. Connection URL: '$connectionUrl'")
        }
        println("Safety guard passed. Connected to: $dbName")

        val hikariDataSource = HikariDataSource().apply {
            jdbcUrl = databaseConfig.dbUrl + "?reWriteBatchedInserts=true"
            username = databaseConfig.dbUsername
            password = databaseConfig.dbPassword
        }
        this.dataSource = hikariDataSource
        val jdbcTemplate = JdbcTemplate(hikariDataSource)

        // --- Krok 2: Stworzenie tabeli testowej ---
        jdbcTemplate.execute("DROP TABLE IF EXISTS performance_test CASCADE;")
        jdbcTemplate.execute(
            """
            CREATE TABLE performance_test (id SERIAL PRIMARY KEY, val1 INT, val2 VARCHAR(50));
            -- Uniwersalny typ-przenośnik - będzie obecny także na zwykłej bazie. Wymagany przez rejestr
            DO $$ BEGIN
                IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'dynamic_dto') THEN
                    CREATE TYPE dynamic_dto AS (type_name text, data_payload jsonb);
                END IF;
            END $$;

        """.trimIndent()
        )
        // --- Krok 3: Inicjalizacja frameworka ---
        this.dataAccess = OctaviusDatabase.fromDataSource(
            dataSource = hikariDataSource,
            packagesToScan = listOf("org.octavius.performance"),
            dbSchemas = databaseConfig.dbSchemas,
            disableFlyway = true,
            disableCoreTypeInitialization = true
        )
        println("Performance test table and composite type created.")

        // --- Krok 4: Rozgrzewka JVM ---
        println("\n--- WARM-UP RUN (10000 wierszy, wyniki ignorowane) ---")
        val warmupData = (1..10000).map { PerformanceTestData(it, "value_$it") }

        methodA_RawJdbcBatch(warmupData)
        dataAccess.rawQuery("TRUNCATE TABLE performance_test RESTART IDENTITY").execute(emptyMap())

        methodB_FrameworkUnnestWithRows(warmupData)
        dataAccess.rawQuery("TRUNCATE TABLE performance_test RESTART IDENTITY").execute(emptyMap())

        methodC_FrameworkUnnestWithParallelArrays(warmupData)
        dataAccess.rawQuery("TRUNCATE TABLE performance_test RESTART IDENTITY").execute(emptyMap())

        // <-- NOWE: Rozgrzewka dla metody D
        methodD_FrameworkUnnestWithTypedArray(warmupData)
        dataAccess.rawQuery("TRUNCATE TABLE performance_test RESTART IDENTITY").execute(emptyMap())

        println("--- WARM-UP COMPLETE ---")
    }

    @ParameterizedTest(name = "Uruchamianie benchmarku dla {0} wierszy...")
    @MethodSource("rowCountsProvider")
    @Order(1)
    fun runBenchmark(rowCount: Int) {
        println("\n--- POMIAR DLA $rowCount WIERSZY (x$ITERATIONS_PER_SIZE iteracji) ---")
        val testData = (1..rowCount).map { PerformanceTestData(it, "value_$it") }

        val jdbcTimings = mutableListOf<Long>()
        val unnestRowTimings = mutableListOf<Long>()
        val unnestParallelTimings = mutableListOf<Long>()
        val unnestTypedArrayTimings = mutableListOf<Long>()

        for (i in 1..ITERATIONS_PER_SIZE) {
            dataAccess.rawQuery("TRUNCATE TABLE performance_test RESTART IDENTITY").execute(emptyMap())
            jdbcTimings.add(measureTimeMillis { methodA_RawJdbcBatch(testData) })

            dataAccess.rawQuery("TRUNCATE TABLE performance_test RESTART IDENTITY").execute(emptyMap())
            unnestRowTimings.add(measureTimeMillis { methodB_FrameworkUnnestWithRows(testData) })

            dataAccess.rawQuery("TRUNCATE TABLE performance_test RESTART IDENTITY").execute(emptyMap())
            unnestParallelTimings.add(measureTimeMillis { methodC_FrameworkUnnestWithParallelArrays(testData) })

            dataAccess.rawQuery("TRUNCATE TABLE performance_test RESTART IDENTITY").execute(emptyMap())
            unnestTypedArrayTimings.add(measureTimeMillis { methodD_FrameworkUnnestWithTypedArray(testData) })
        }

        allJdbcResults[rowCount] = jdbcTimings
        allUnnestRowResults[rowCount] = unnestRowTimings
        allUnnestParallelResults[rowCount] = unnestParallelTimings
        allUnnestTypedArrayResults[rowCount] = unnestTypedArrayTimings
    }

    @AfterAll
    fun printResults() {
        println("\n\n--- OSTATECZNE WYNIKI BENCHMARKU (średni czas z $ITERATIONS_PER_SIZE iteracji) ---")
        println("===========================================================================================================")
        println("| Liczba wierszy | Metoda A (JDBC) | Metoda B (ROW) | Metoda C (Parallel) | Metoda D (TypedArray) |")
        println("|----------------|-----------------|----------------|---------------------|-----------------------|")

        val sortedKeys = rowCountsProvider().sorted()
        for (key in sortedKeys) {
            val avgJdbc = allJdbcResults[key]?.average()?.toLong() ?: -1
            val avgRow = allUnnestRowResults[key]?.average()?.toLong() ?: -1
            val avgParallel = allUnnestParallelResults[key]?.average()?.toLong() ?: -1
            val avgTypedArray = allUnnestTypedArrayResults[key]?.average()?.toLong() ?: -1

            val keyStr = key.toString().padStart(14)
            val jdbcStr = "$avgJdbc ms".padStart(15)
            val rowStr = "$avgRow ms".padStart(14)
            val parallelStr = "$avgParallel ms".padStart(19)
            val typedArrayStr = "$avgTypedArray ms".padStart(21)

            println("|$keyStr |$jdbcStr |$rowStr |$parallelStr |$typedArrayStr |")
        }
        println("===========================================================================================================")
    }

    // --- Metody do testowania ---

    private fun methodA_RawJdbcBatch(data: List<PerformanceTestData>) {
        val sql = "INSERT INTO performance_test (val1, val2) VALUES (?, ?)"
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(sql).use { stmt ->
                for (item in data) {
                    stmt.setInt(1, item.val1)
                    stmt.setString(2, item.val2)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
            conn.commit()
        }
    }

    private fun methodB_FrameworkUnnestWithRows(data: List<PerformanceTestData>) {
        val sql = """
            INSERT INTO performance_test (val1, val2)
            SELECT (rec).val1, (rec).val2 FROM (
                SELECT UNNEST(:data::performance_test[]) as rec
            ) as t
        """.trimIndent()
        dataAccess.rawQuery(sql).execute(mapOf("data" to data))
    }

    private fun methodC_FrameworkUnnestWithParallelArrays(data: List<PerformanceTestData>) {
        val sql = """
            INSERT INTO performance_test (val1, val2)
            SELECT * FROM UNNEST(:val1_list, :val2_list)
        """.trimIndent()
        val val1List = data.map { it.val1 }
        val val2List = data.map { it.val2 }
        val params = mapOf("val1_list" to val1List, "val2_list" to val2List)
        dataAccess.rawQuery(sql).execute(params)
    }

    private fun methodD_FrameworkUnnestWithTypedArray(data: List<PerformanceTestData>) {
        val sql = """
            INSERT INTO performance_test (val1, val2)
            SELECT * FROM UNNEST(:val1_array, :val2_array)
        """.trimIndent()
        val val1Array = data.map { it.val1 }.toTypedArray()
        val val2Array = data.map { it.val2 }.toTypedArray()
        val params = mapOf("val1_array" to val1Array, "val2_array" to val2Array)
        dataAccess.rawQuery(sql).execute(params)
    }
}