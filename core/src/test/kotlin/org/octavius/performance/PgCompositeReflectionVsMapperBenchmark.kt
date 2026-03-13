package org.octavius.performance

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.octavius.data.DataAccess
import org.octavius.data.builder.toColumn
import org.octavius.data.getOrThrow
import org.octavius.database.OctaviusDatabase
import org.octavius.database.config.DatabaseConfig
import org.octavius.database.config.DynamicDtoSerializationStrategy
import org.octavius.domain.test.reflvsmap.CharMap
import org.octavius.domain.test.reflvsmap.CharRefl
import org.octavius.domain.test.reflvsmap.StatsMap
import org.octavius.domain.test.reflvsmap.StatsRefl
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import kotlin.system.measureTimeMillis


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Disabled
class PgCompositeReflectionVsMapperBenchmark {

    private val ITERATIONS_PER_SIZE = 5
    private val rowResults = ConcurrentHashMap<Int, Map<String, Long>>()

    private lateinit var dataSource: DataSource
    private lateinit var dataAccess: DataAccess

    companion object {
        @JvmStatic
        fun rowCountsProvider(): List<Int> = listOf(10_000,25_000, 50_000, 80_000)
    }

    @BeforeAll
    fun setup() {
        val config = DatabaseConfig.loadFromFile("test-database.properties")
        this.dataSource = HikariDataSource().apply {
            jdbcUrl = config.dbUrl
            username = config.dbUsername
            password = config.dbPassword
            maximumPoolSize = 5
        }

        val jdbcTemplate = JdbcTemplate(dataSource)
        jdbcTemplate.execute("DROP TABLE IF EXISTS perf_refl CASCADE")
        jdbcTemplate.execute("DROP TABLE IF EXISTS perf_map CASCADE")
        jdbcTemplate.execute("DROP TYPE IF EXISTS perf_char_refl CASCADE")
        jdbcTemplate.execute("DROP TYPE IF EXISTS perf_stats_refl CASCADE")
        jdbcTemplate.execute("DROP TYPE IF EXISTS perf_char_map CASCADE")
        jdbcTemplate.execute("DROP TYPE IF EXISTS perf_stats_map CASCADE")
        
        jdbcTemplate.execute("CREATE TYPE perf_stats_refl AS (strength INT, agility INT, intelligence INT)")
        jdbcTemplate.execute("CREATE TYPE perf_char_refl AS (id INT, name TEXT, stats perf_stats_refl)")
        jdbcTemplate.execute("CREATE TYPE perf_stats_map AS (strength INT, agility INT, intelligence INT)")
        jdbcTemplate.execute("CREATE TYPE perf_char_map AS (id INT, name TEXT, stats perf_stats_map)")
        
        jdbcTemplate.execute("CREATE TABLE perf_refl (data perf_char_refl)")
        jdbcTemplate.execute("CREATE TABLE perf_map (data perf_char_map)")

        this.dataAccess = OctaviusDatabase.fromDataSource(
            dataSource,
            listOf("org.octavius.domain.test.reflvsmap"),
            config.dbSchemas,
            DynamicDtoSerializationStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS,
            disableFlyway = true,
            disableCoreTypeInitialization = true
        )

        // Rozgrzewka
        println("Rozgrzewka...")
        val rowCount = 1000
        val reflData = (1..rowCount).map { CharRefl(it, "name_$it", StatsRefl(it % 100, it % 50, it % 200)) }
        val mapData = (1..rowCount).map { CharMap(it, "name_$it", StatsMap(it % 100, it % 50, it % 200)) }
        repeat(5) {
            dataAccess.rawQuery("TRUNCATE TABLE perf_refl").execute()
            dataAccess.rawQuery("INSERT INTO perf_refl (data) SELECT UNNEST(:data)").execute(mapOf("data" to reflData))
            dataAccess.rawQuery("TRUNCATE TABLE perf_map").execute()
            dataAccess.rawQuery("INSERT INTO perf_map (data) SELECT UNNEST(:data)").execute(mapOf("data" to mapData))
            dataAccess.select("data").from("perf_refl").toColumn<CharRefl>().getOrThrow()
            dataAccess.select("data").from("perf_map").toColumn<CharMap>().getOrThrow()
        }
        println("Rozgrzewka zakończona.")
    }

    @ParameterizedTest
    @MethodSource("rowCountsProvider")
    @Order(1)
    fun runBenchmark(rowCount: Int) {
        println("Pomiar dla $rowCount wierszy...")
        val reflData = (1..rowCount).map { CharRefl(it, "name_$it", StatsRefl(it % 100, it % 50, it % 200)) }
        val mapData = (1..rowCount).map { CharMap(it, "name_$it", StatsMap(it % 100, it % 50, it % 200)) }

        val timings = mutableMapOf<String, Long>()

        // 1. Zapis Reflection
        timings["ins_refl"] = measureAvg(ITERATIONS_PER_SIZE) {
            dataAccess.rawQuery("TRUNCATE TABLE perf_refl").execute()
            dataAccess.rawQuery("INSERT INTO perf_refl (data) SELECT UNNEST(:data)").execute(mapOf("data" to reflData))
        }

        // 2. Zapis Mapper
        timings["ins_map"] = measureAvg(ITERATIONS_PER_SIZE) {
            dataAccess.rawQuery("TRUNCATE TABLE perf_map").execute()
            dataAccess.rawQuery("INSERT INTO perf_map (data) SELECT UNNEST(:data)").execute(mapOf("data" to mapData))
        }

        // 3. Odczyt Reflection
        timings["read_refl"] = measureAvg(ITERATIONS_PER_SIZE) {
            dataAccess.select("data").from("perf_refl").toColumn<CharRefl>().getOrThrow()
        }

        // 4. Odczyt Mapper
        timings["read_map"] = measureAvg(ITERATIONS_PER_SIZE) {
            dataAccess.select("data").from("perf_map").toColumn<CharMap>().getOrThrow()
        }

        rowResults[rowCount] = timings
    }

    private fun measureAvg(iterations: Int, block: () -> Unit): Long {
        val times = mutableListOf<Long>()
        repeat(iterations) {
            times.add(measureTimeMillis(block))
        }
        return times.average().toLong()
    }

    @AfterAll
    fun printResults() {
        println("\n--- RESULTS: Reflection vs Mapper ---")
        rowCountsProvider().sorted().forEach { rowCount ->
            val t = rowResults[rowCount] ?: return@forEach
            println("Rows: $rowCount | Insert: Refl=${t["ins_refl"]}ms, Map=${t["ins_map"]}ms | Read: Refl=${t["read_refl"]}ms, Map=${t["read_map"]}ms")
        }
    }
}
