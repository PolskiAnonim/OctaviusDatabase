package org.octavius.performance

import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.octavius.data.DataAccess
import org.octavius.data.annotation.DynamicallyMappable
import org.octavius.data.annotation.PgComposite
import org.octavius.data.builder.toColumn
import org.octavius.database.OctaviusDatabase
import org.octavius.database.config.DatabaseConfig
import org.octavius.database.config.DynamicDtoSerializationStrategy
import org.octavius.domain.test.compositevsdynamic.DynamicCharacter
import org.octavius.domain.test.compositevsdynamic.DynamicStats
import org.octavius.domain.test.compositevsdynamic.PgCharacter
import org.octavius.domain.test.compositevsdynamic.PgStats
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import kotlin.system.measureTimeMillis

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Disabled
class PgCompositeVsDynamicDtoBenchmark {

    // --- Konfiguracja Benchmarku ---
    private val ITERATIONS_PER_SIZE = 10
    private val FILTER_THRESHOLD = 95 // Filtrujemy postacie z siłą > 95

    // --- Zmienne przechowujące wyniki (czas w ms) ---
    private val insertResults = ConcurrentHashMap<Int, Pair<MutableList<Long>, MutableList<Long>>>()
    private val fullReadResults = ConcurrentHashMap<Int, Pair<MutableList<Long>, MutableList<Long>>>()
    private val filterReadResults = ConcurrentHashMap<Int, Pair<MutableList<Long>, MutableList<Long>>>()

    // --- Zmienne konfiguracyjne ---
    private lateinit var dataSource: DataSource
    private lateinit var dataAccess: DataAccess

    companion object {
        @JvmStatic
        fun rowCountsProvider(): List<Int> = listOf(10_000, 20_000, 40_000, 50_000)
    }

    @BeforeAll
    fun setup() {
        println("--- ROZPOCZYNANIE KONFIGURACJI BENCHMARKU: @PgComposite vs @DynamicallyMappable ---")

        val databaseConfig = DatabaseConfig.loadFromFile("test-database.properties")
        val connectionUrl = databaseConfig.dbUrl
        val dbName = connectionUrl.substringAfterLast("/")
        if (!connectionUrl.contains("localhost:5432") || dbName != "octavius_test") {
            throw IllegalStateException("ABORTING TEST! Safety guard failed. Connection URL: '$connectionUrl'")
        }
        println("Safety guard passed. Connected to: $dbName")

        val hikariDataSource = HikariDataSource().apply {
            jdbcUrl = databaseConfig.dbUrl
            username = databaseConfig.dbUsername
            password = databaseConfig.dbPassword
            maximumPoolSize = 5
        }
        this.dataSource = hikariDataSource

        // Inicjalizacja schematu
        val initSql = String(Files.readAllBytes(Paths.get(this::class.java.classLoader.getResource("init-composite-vs-dynamic-benchmark-db.sql")!!.toURI())))
        val jdbcTemplate = hikariDataSource.let { org.springframework.jdbc.core.JdbcTemplate(it) }
        jdbcTemplate.execute(initSql)
        println("Performance test schema for Composite vs Dynamic DTO created.")

        // Inicjalizacja frameworka Octavius
        this.dataAccess = OctaviusDatabase.fromDataSource(
            dataSource,
            // Skanujemy pakiet, w którym zdefiniowane są nasze klasy testowe
            listOf("org.octavius.domain.test.compositevsdynamic"),
            databaseConfig.dbSchemas,
            DynamicDtoSerializationStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS,
            disableFlyway = true,
            disableCoreTypeInitialization = true
        )

        // Rozgrzewka JVM
        println("\n--- WARM-UP RUN (1000 wierszy, wyniki ignorowane) ---")
        val warmupPgData = (1..1000).map { PgCharacter(it, "warmup_$it", PgStats(it % 100, it % 50, it % 200)) }
        val warmupDynData = (1..1000).map {
            DynamicCharacter(
                it,
                "warmup_$it",
                DynamicStats(it % 100, it % 50, it % 200)
            )
        }
        runAllMethods(warmupPgData, warmupDynData)
        println("--- WARM-UP COMPLETE ---")
    }

    @ParameterizedTest(name = "Uruchamianie benchmarku dla {0} wierszy...")
    @MethodSource("rowCountsProvider")
    @Order(1)
    fun runBenchmark(rowCount: Int) {
        println("\n--- POMIAR DLA $rowCount WIERSZY (x$ITERATIONS_PER_SIZE iteracji) ---")
        val pgData = (1..rowCount).map { PgCharacter(it, "char_$it", PgStats(it % 100, it % 50, it % 200)) }
        val dynData = (1..rowCount).map { DynamicCharacter(it, "char_$it", DynamicStats(it % 100, it % 50, it % 200)) }

        val pgInsert = mutableListOf<Long>()
        val dynInsert = mutableListOf<Long>()
        val pgRead = mutableListOf<Long>()
        val dynRead = mutableListOf<Long>()
        val pgFilter = mutableListOf<Long>()
        val dynFilter = mutableListOf<Long>()

        for (i in 1..ITERATIONS_PER_SIZE) {
            val array = runAllMethods(pgData, dynData)
            pgInsert.add(array[0])
            dynInsert.add(array[1])
            pgRead.add(array[2])
            dynRead.add(array[3])
            pgFilter.add(array[4])
            dynFilter.add(array[5])
        }

        insertResults[rowCount] = pgInsert to dynInsert
        fullReadResults[rowCount] = pgRead to dynRead
        filterReadResults[rowCount] = pgFilter to dynFilter
    }

    private fun runAllMethods(pgData: List<PgCharacter>, dynData: List<DynamicCharacter>): LongArray {
        val timings = LongArray(6)

        // 1. Zapis
        truncateTables()
        timings[0] = measureTimeMillis { insertPgComposite(pgData) }
        timings[1] = measureTimeMillis { insertDynamicDto(dynData) }

        // 2. Pełny odczyt
        timings[2] = measureTimeMillis { readAllPgComposite() }
        timings[3] = measureTimeMillis { readAllDynamicDto() }

        // 3. Odczyt z filtrowaniem
        timings[4] = measureTimeMillis { filterPgComposite(FILTER_THRESHOLD) }
        timings[5] = measureTimeMillis { filterDynamicDto(FILTER_THRESHOLD) }

        return timings
    }

    @AfterAll
    fun printResults() {
        println("\n\n--- OSTATECZNE WYNIKI BENCHMARKU: @PgComposite vs @DynamicallyMappable (średni czas z $ITERATIONS_PER_SIZE iteracji) ---")
        val header = "| Liczba Wierszy | Zapis (PG) | Zapis (DYN) | Odczyt (PG) | Odczyt (DYN) | Filtr (PG) | Filtr (DYN) |"
        val separator = "—".repeat(header.length)
        println(separator)
        println(header)
        println(separator)

        rowCountsProvider().sorted().forEach { key ->
            val avgInsPg = insertResults[key]?.first?.average()?.toLong() ?: -1
            val avgInsDyn = insertResults[key]?.second?.average()?.toLong() ?: -1
            val avgReadPg = fullReadResults[key]?.first?.average()?.toLong() ?: -1
            val avgReadDyn = fullReadResults[key]?.second?.average()?.toLong() ?: -1
            val avgFilterPg = filterReadResults[key]?.first?.average()?.toLong() ?: -1
            val avgFilterDyn = filterReadResults[key]?.second?.average()?.toLong() ?: -1

            println(
                "| ${key.toString().padEnd(14)} " +
                        "| ${"$avgInsPg ms".padEnd(10)} " +
                        "| ${"$avgInsDyn ms".padEnd(11)} " +
                        "| ${"$avgReadPg ms".padEnd(11)} " +
                        "| ${"$avgReadDyn ms".padEnd(12)} " +
                        "| ${"$avgFilterPg ms".padEnd(11)} " +
                        "| ${"$avgFilterDyn ms".padEnd(12)} |"
            )
        }
        println(separator)
    }

    // --- Metody realizujące operacje ---

    private fun truncateTables() {
        dataAccess.rawQuery("TRUNCATE TABLE performance_pg_composite").execute()
        dataAccess.rawQuery("TRUNCATE TABLE performance_dynamic_dto").execute()
    }

    // --- ZAPIS ---
    private fun insertPgComposite(data: List<PgCharacter>) {
        val sql = "INSERT INTO performance_pg_composite (data) SELECT UNNEST(:data)"
        dataAccess.rawQuery(sql).execute(mapOf("data" to data))
    }

    private fun insertDynamicDto(data: List<DynamicCharacter>) {
        val sql = "INSERT INTO performance_dynamic_dto (data) SELECT UNNEST(:data)"
        dataAccess.rawQuery(sql).execute(mapOf("data" to data))
    }

    // --- ODCZYT ---
    private fun readAllPgComposite() {
        dataAccess.select("data").from("performance_pg_composite").toColumn<PgCharacter>()
    }

    private fun readAllDynamicDto() {
        dataAccess.select("data").from("performance_dynamic_dto").toColumn<DynamicCharacter>()
    }

    // --- FILTROWANIE ---
    private fun filterPgComposite(minStrength: Int) {
        // Dostęp do zagnieżdżonego pola w typie kompozytowym: (kolumna).pole.zagn_pole
        val sql = "SELECT data FROM performance_pg_composite WHERE ((data).stats).strength > :min_strength"
        dataAccess.rawQuery(sql).toColumn<PgCharacter>(mapOf("min_strength" to minStrength))
    }

    private fun filterDynamicDto(minStrength: Int) {
        // Dostęp do zagnieżdżonego pola w JSONB: (kolumna).data_payload -> 'pole' ->> 'zagn_pole'
        // Operator '->>' zwraca text, więc musimy go rzutować na integer do porównania.
        val sql = "SELECT data FROM performance_dynamic_dto WHERE ((data).data_payload -> 'stats' ->> 'strength')::int > :min_strength"
        dataAccess.rawQuery(sql).toColumn<DynamicCharacter>(mapOf("min_strength" to minStrength))
    }
}