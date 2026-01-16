package org.octavius.performance

import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.octavius.database.type.PostgresToKotlinConverter
import org.octavius.database.type.utils.createFakeTypeRegistry
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureNanoTime

/**
 * Benchmark wydajności dla PostgresToKotlinConverter.
 *
 * Mierzy czas parsowania złożonych, zagnieżdżonych struktur tekstowych
 * (reprezentujących tablice typów kompozytowych z PostgreSQL) na obiekty Kotlina.
 *
 * Metodologia:
 * 1. Test jest w pełni izolowany od bazy danych.
 * 2. Przeprowadzany jest "warm-up" (rozgrzewka) JVM, aby wyniki nie były zakłócone
 *    przez kompilację JIT i inicjalizację cache'y.
 * 3. Dla każdej liczby obiektów test jest powtarzany wielokrotnie, a wynik jest uśredniany.
 * 4. Wyniki prezentowane są w formie tabeli, uwzględniając średni czas i przepustowość (obiekty/sek).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ConverterPerformanceBenchmark {

    // --- Konfiguracja Benchmarku ---
    private val ITERATIONS_PER_SIZE = 20 // Zwiększona liczba iteracji dla większej dokładności

    // --- Zmienne przechowujące wyniki (czas w nanosekundach) ---
    private val parsingResults = ConcurrentHashMap<Int, MutableList<Long>>()

    // --- Komponenty testowane ---
    private lateinit var converter: PostgresToKotlinConverter

    companion object {
        // Definiujemy liczbę obiektów (projektów) do sparsowania w jednym wywołaniu
        @JvmStatic
        fun projectCountsProvider(): List<Int> = listOf(1, 10, 50, 100, 250, 500, 1000, 5000, 10000, 15000, 20000)

        // Wyciągnięte pojedyncze reprezentacje projektów z "potworka"
        // Posłużą jako klocki do budowania dużych stringów testowych
        private const val SINGLE_PROJECT_STRING_1 = "\"(\"Complex \"\"Enterprise\"\" Project\",\"A very complex project with all possible data types and \"\"special characters\"\"\",active,\"{\"\"(\\\\\"\"Project Manager\\\\\"\",40,pm@example.com,t,\\\\\"\"{manager,stakeholder}\\\\\"\")\"\",\"\"(\\\\\"\"Senior Dev \\\\\"\"\\\\\"\"The Architect\\\\\"\"\\\\\"\"\\\\\"\",35,senior@example.com,t,\\\\\"\"{architect,senior-dev}\\\\\"\")\"\",\"\"(\\\\\"\"Junior Dev\\\\\"\",24,junior@example.com,t,\\\\\"\"{junior-dev,learner}\\\\\"\")\"\",\"\"(,30,\\\\\"\"\\\\\"\",t,{user})\"\"}\",\"{\"\"(1,\\\\\"\"Setup \\\\\"\"\\\\\"\"Development\\\\\"\"\\\\\"\" Environment\\\\\"\",\\\\\"\"Configure all development tools and \\\\\"\"\\\\\"\"databases\\\\\"\"\\\\\"\"\\\\\"\",active,high,enhancement,\\\\\"\"(\\\\\"\"\\\\\"\"DevOps Guy\\\\\"\"\\\\\"\",32,devops@example.com,t,\\\\\"\"\\\\\"\"{devops,infrastructure}\\\\\"\"\\\\\"\")\\\\\"\",\\\\\"\"(\\\\\"\"\\\\\"\"2024-01-01 09:00:00\\\\\"\"\\\\\"\",\\\\\"\"\\\\\"\"2024-01-15 14:30:00\\\\\"\"\\\\\"\",1,\\\\\"\"\\\\\"\"{setup,infrastructure,priority}\\\\\"\"\\\\\"\")\\\\\"\",\\\\\"\"{\\\\\"\"\\\\\"\"install docker\\\\\"\"\\\\\"\",\\\\\"\"\\\\\"\"setup database\\\\\"\"\\\\\"\",\\\\\"\"\\\\\"\"configure \\\\\\\\\\\\\\\\\\\\\"\"\\\\\"\"CI/CD\\\\\\\\\\\\\\\\\\\\\"\"\\\\\"\"\\\\\"\"\\\\\"\"}\\\\\"\",16.5)\"\",\"\"(2,\\\\\"\"Implement \\\\\"\"\\\\\"\"Core\\\\\"\"\\\\\"\" Features\\\\\"\",\\\\\"\"Build the main functionality with proper \\\\\"\"\\\\\"\"error handling\\\\\"\"\\\\\"\"\\\\\"\",pending,critical,feature,\\\\\"\"(\\\\\"\"\\\\\"\"Lead Developer\\\\\"\"\\\\\"\",38,lead@example.com,t,\\\\\"\"\\\\\"\"{lead,full-stack}\\\\\"\"\\\\\"\")\\\\\"\",\\\\\"\"(\\\\\"\"\\\\\"\"2024-01-10 10:00:00\\\\\"\"\\\\\"\",\\\\\"\"\\\\\"\"2024-01-20 16:00:00\\\\\"\"\\\\\"\",2,\\\\\"\"\\\\\"\"{core,critical,feature}\\\\\"\"\\\\\"\")\\\\\"\",\\\\\"\"{\\\\\"\"\\\\\"\"design API\\\\\"\"\\\\\"\",\\\\\"\"\\\\\"\"implement \\\\\\\\\\\\\\\\\\\\\"\"\\\\\"\"business logic\\\\\\\\\\\\\\\\\\\\\"\"\\\\\"\"\\\\\"\"\\\\\"\",\\\\\"\"\\\\\"\"add tests\\\\\"\"\\\\\"\",\\\\\"\"\\\\\"\"write \\\\\\\\\\\\\\\\\\\\\"\"\\\\\"\"documentation\\\\\\\\\\\\\\\\\\\\\"\"\\\\\"\"\\\\\"\"\\\\\"\"}\\\\\"\",40.0)\"\"}\",\"(\"\"2024-01-01 08:00:00\"\",\"\"2024-01-15 14:30:00\"\",3,\"\"{enterprise,complex,multi-team,high-priority}\"\")\",150000.50)\""
        private const val SINGLE_PROJECT_STRING_2 = "\"(\"Research \"\"Innovation\"\" Project\",\"Experimental features and \"\"new technologies\"\"\",active,\"{\"\"(\\\\\"\"Researcher 'The Innovator'\\\\\"\",33,research@example.com,t,\\\\\"\"{researcher,innovator}\\\\\"\")\"\",\"\"(\\\\\"\"Data Scientist\\\\\"\",27,data@example.com,t,\\\\\"\"{data-science,ml}\\\\\"\")\"\"}\",\"{\"\"(20,\\\\\"\"Prototype \\\\\"\"\\\\\"\"AI\\\\\"\"\\\\\"\" Integration\\\\\"\",\\\\\"\"Build proof of concept for \\\\\"\"\\\\\"\"machine learning\\\\\"\"\\\\\"\" features\\\\\"\",active,medium,feature,\\\\\"\"(\\\\\"\"\\\\\"\"AI Specialist\\\\\"\"\\\\\"\",30,ai@example.com,t,\\\\\"\"\\\\\"\"{ai,ml,python}\\\\\"\"\\\\\"\")\\\\\"\",\\\\\"\"(\\\\\"\"\\\\\"\"2024-01-05 10:00:00\\\\\"\"\\\\\"\",\\\\\"\"\\\\\"\"2024-01-15 15:00:00\\\\\"\"\\\\\"\",5,\\\\\"\"\\\\\"\"{ai,prototype,experimental}\\\\\"\"\\\\\"\")\\\\\"\",\\\\\"\"{\\\\\"\"\\\\\"\"research \\\\\\\\\\\\\\\\\\\\\"\"\\\\\"\"algorithms\\\\\\\\\\\\\\\\\\\\\"\"\\\\\"\"\\\\\"\"\\\\\"\",\\\\\"\"\\\\\"\"build model\\\\\"\"\\\\\"\",\\\\\"\"\\\\\"\"integrate \\\\\\\\\\\\\\\\\\\\\"\"\\\\\"\"with backend\\\\\\\\\\\\\\\\\\\\\"\"\\\\\"\"\\\\\"\"\\\\\"\",\\\\\"\"\\\\\"\"test \\\\\\\\\\\\\\\\\\\\\"\"\\\\\"\"accuracy\\\\\\\\\\\\\\\\\\\\\"\"\\\\\"\"\\\\\"\"\\\\\"\"}\\\\\"\",60.0)\"\"}\",\"(\"\"2024-01-05 10:00:00\"\",\"\"2024-01-15 15:00:00\"\",5,\"\"{research,innovation,ai}\"\")\",75000.25)\""

    }

    @BeforeAll
    fun setup() {
        println("--- ROZPOCZYNANIE KONFIGURACJI BENCHMARKU PARSOWANIA ---")
        val fakeTypeRegistry = createFakeTypeRegistry() // Używamy fałszywego rejestru, jak w testach jednostkowych
        this.converter = PostgresToKotlinConverter(fakeTypeRegistry)

        println("\n--- WARM-UP RUN (500 projektów, wyniki ignorowane) ---")
        val warmupString = buildTestArrayString(500)
        // Wykonajmy kilka razy, aby mieć pewność, że wszystko jest "gorące"
        repeat(10) {
            converter.convert(warmupString, "_test_project")
        }
        println("--- WARM-UP COMPLETE ---")
    }

    @ParameterizedTest(name = "Uruchamianie benchmarku parsowania dla {0} projektów...")
    @MethodSource("projectCountsProvider")
    @Order(1)
    fun runParsingBenchmark(projectCount: Int) {
        println("\n--- POMIAR DLA $projectCount PROJEKTÓW (x$ITERATIONS_PER_SIZE iteracji) ---")
        val testString = buildTestArrayString(projectCount)
        val timings = mutableListOf<Long>()

        repeat(ITERATIONS_PER_SIZE) {
            val time = measureNanoTime {
                converter.convert(testString, "_test_project")
            }
            timings.add(time)
        }
        parsingResults[projectCount] = timings
    }

    @AfterAll
    fun printResults() {
        println("\n\n--- OSTATECZNE WYNIKI BENCHMARKU PARSOWANIA ---")
        println("==================================================================================")
        println("| Liczba obiektów | Średni czas (ms) | Obiekty na sekundę (ops/sec) |")
        println("|-----------------|------------------|------------------------------|")

        val sortedKeys = projectCountsProvider().sorted()
        for (key in sortedKeys) {
            val avgNanos = parsingResults[key]?.average() ?: -1.0
            val avgMillis = avgNanos / 1_000_000.0

            // Przepustowość: (liczba obiektów / czas w sekundach)
            val throughput = if (avgNanos > 0) (key / (avgNanos / 1_000_000_000.0)).toLong() else 0

            val keyStr = key.toString().padStart(15)
            val avgStr = String.format("%.3f ms", avgMillis).padStart(16)
            val throughputStr = "$throughput ops/sec".padStart(28)

            println("|$keyStr |$avgStr |$throughputStr |")
        }
        println("==================================================================================")
        println("* ops/sec = operacje (sparsowane obiekty) na sekundę. Wyższe wartości są lepsze.")
    }

    /**
     * Buduje poprawny string reprezentujący tablicę PostgreSQL
     * o zadanej liczbie elementów, używając predefiniowanych "potworków".
     */
    private fun buildTestArrayString(count: Int): String {
        if (count == 0) return "{}"
        val content = StringBuilder()
        for (i in 1..count) {
            // Używamy naprzemiennie dwóch różnych stringów, aby uniknąć ewentualnych optymalizacji
            // kompresji dla powtarzających się danych
            content.append(if (i % 2 == 0) SINGLE_PROJECT_STRING_1 else SINGLE_PROJECT_STRING_2)
            if (i < count) {
                content.append(',')
            }
        }
        return "{$content}"
    }
}