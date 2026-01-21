package org.octavius.database.type.pgtype

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.datetime.LocalDateTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.database.RowMappers
import org.octavius.database.config.DatabaseConfig
import org.octavius.database.type.KotlinToPostgresConverter
import org.octavius.database.type.registry.TypeRegistryLoader
import org.octavius.domain.test.pgtype.*
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.reflect.typeOf

/**
 * Test integracyjny dla konwertera Kotlin -> PostgreSQL.
 *
 * Ta klasa testowa weryfikuje pełny cykl życia danych:
 * 1. Tworzy złożone obiekty w Kotlinie.
 * 2. Używa `KotlinToPostgresConverter` do "spłaszczenia" ich na potrzeby zapytania SQL.
 * 3. Zapisuje je w prawdziwej bazie danych PostgreSQL (INSERT/UPDATE).
 * 4. Odczytuje zapisane dane z powrotem.
 * 5. Porównuje odczytany obiekt z oryginałem, aby zapewnić 100% zgodność.
 *
 * UWAGA: Ten test wykonuje operacje modyfikujące dane (DELETE, INSERT, UPDATE).
 * Posiada zabezpieczenie, aby uruchomić się wyłącznie na dedykowanej bazie testowej.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealPostgresDataModificationTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var kotlinToPostgresConverter: KotlinToPostgresConverter
    private lateinit var mappers: RowMappers // Potrzebne do odczytu w celu weryfikacji

    @BeforeAll
    fun setup() {
        val databaseConfig = DatabaseConfig.loadFromFile("test-database.properties")

        val connectionUrl = databaseConfig.dbUrl
        val dbName = connectionUrl.substringAfterLast("/")
        if (!connectionUrl.contains("localhost:5432") || dbName != "octavius_test") {
            throw IllegalStateException(
                "ABORTING TEST! Attempting to run destructive tests on a non-test database. URL: '$connectionUrl'"
            )
        }
        println("Safety guard passed for modification tests. Connected to: $dbName")

        // --- Krok 2: Połączenie i inicjalizacja schematu ---
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = databaseConfig.dbUrl
            username = databaseConfig.dbUsername
            password = databaseConfig.dbPassword
        }
        val dataSource = HikariDataSource(hikariConfig)
        jdbcTemplate = JdbcTemplate(dataSource)

        jdbcTemplate.execute("DROP SCHEMA IF EXISTS public CASCADE;")
        jdbcTemplate.execute("CREATE SCHEMA public;")
        val initSql = String(
            Files.readAllBytes(
                Paths.get(
                    this::class.java.classLoader.getResource("init-complex-test-db.sql")!!.toURI()
                )
            )
        )
        jdbcTemplate.execute(initSql)
        println("Complex test DB schema and data initialized successfully.")

        // --- Krok 3: Inicjalizacja obu konwerterów ---
        val typeRegistry =
            TypeRegistryLoader(
                jdbcTemplate,
                listOf("org.octavius.domain.test.pgtype"),
                databaseConfig.dbSchemas
            ).load()
        kotlinToPostgresConverter = KotlinToPostgresConverter(typeRegistry)
        mappers = RowMappers(typeRegistry)
    }

    @BeforeEach
    fun cleanup() {
        // Czyści tabelę przed każdym testem, zostawiając tylko oryginalny "złoty" rekord
        // To zapewnia, że testy są od siebie niezależne.
        jdbcTemplate.update("DELETE FROM complex_test_data WHERE id > 1")
    }

    @Test
    fun `should insert and then retrieve an entire complex object`() {
        // Arrange: Tworzymy nowy, w pełni zagnieżdżony obiekt projektu
        val newProject = createSampleProject()

        val sql = """
            INSERT INTO complex_test_data (
                simple_text, simple_number, simple_bool, project_data, person_array
            ) VALUES (
                :text, :number, :bool, :project, :persons
            ) RETURNING id
        """.trimIndent()

        val params = mapOf(
            "text" to "New Project Entry",
            "number" to 101,
            "bool" to true,
            "project" to newProject,
            "persons" to newProject.teamMembers
        )

        // Act: Konwertujemy i wykonujemy zapytanie INSERT
        val expandedQuery = kotlinToPostgresConverter.expandParametersInQuery(sql, params)
        val newId = jdbcTemplate.queryForObject(
            expandedQuery.sql,
            Long::class.java,
            *expandedQuery.params.toTypedArray()
        )
        assertThat(newId).isNotNull()

        // Assert: Odczytujemy wstawiony wiersz i porównujemy z oryginałem
        val retrievedMap = jdbcTemplate.queryForObject(
            "SELECT project_data, person_array FROM complex_test_data WHERE id = ?",
            mappers.ColumnNameMapper(),
            newId
        )

        val retrievedProject = retrievedMap["project_data"] as TestProject
        val retrievedPersons = retrievedMap["person_array"] as List<TestPerson>

        assertThat(retrievedProject).isEqualTo(newProject)
        assertThat(retrievedPersons).isEqualTo(newProject.teamMembers)
    }

    @Test
    fun `should update a complex array field in an existing row`() {
        // Arrange: Tworzymy nową listę członków zespołu, którą chcemy zaktualizować
        val newTeam = listOf(
            TestPerson("Charlie Day", 45, "charlie@paddys.pub", true, listOf("wildcard")),
            TestPerson("Frank Reynolds", 70, "frank@warthog.com", true, listOf("financier", "mastermind"))
        )

        val sql = "UPDATE complex_test_data SET person_array = :newTeam WHERE id = 1"
        val params = mapOf("newTeam" to newTeam)

        // Act: Konwertujemy i wykonujemy zapytanie UPDATE
        val expandedQuery = kotlinToPostgresConverter.expandParametersInQuery(sql, params)
        val updatedRows = jdbcTemplate.update(expandedQuery.sql, *expandedQuery.params.toTypedArray())
        assertThat(updatedRows).isEqualTo(1)

        // Assert: Odczytujemy tylko zaktualizowane pole i weryfikujemy
        val retrievedTeam = jdbcTemplate.queryForObject(
            "SELECT person_array FROM complex_test_data WHERE id = 1",
            mappers.SingleValueMapper<List<TestPerson>>(typeOf<List<TestPerson>>())
        ) as List<TestPerson>

        assertThat(retrievedTeam).isEqualTo(newTeam)
    }

    private fun createSampleProject(): TestProject {
        return TestProject(
            name = "Project \"Phoenix\"",
            description = "A project to test data serialization/deserialization.",
            status = TestStatus.Pending,
            teamMembers = listOf(
                TestPerson("Dr. Alan Grant", 55, "alan.grant@jurassic.park", true, listOf("paleontologist")),
                TestPerson("Dr. Ellie Sattler", 48, "ellie.sattler@jurassic.park", true, listOf("paleobotanist"))
            ),
            tasks = listOf(
                TestTask(
                    id = 1001,
                    title = "Secure the 'Raptor' enclosure",
                    description = "High priority task, involves complex logic.",
                    status = TestStatus.Pending,
                    priority = TestPriority.Critical,
                    category = TestCategory.BugFix,
                    assignee = TestPerson("Robert Muldoon", 45, "muldoon@jurassic.park", true, listOf("game_warden")),
                    metadata = TestMetadata(
                        createdAt = LocalDateTime(2023, 10, 26, 9, 0),
                        updatedAt = LocalDateTime(2023, 10, 26, 11, 30),
                        version = 2,
                        tags = listOf("security", "critical-path")
                    ),
                    subtasks = listOf("Check fence integrity", "Verify power grid"),
                    estimatedHours = BigDecimal("8.0")
                )
            ),
            metadata = TestMetadata(
                createdAt = LocalDateTime(2023, 10, 1, 0, 0),
                updatedAt = LocalDateTime(2023, 10, 26, 12, 0),
                version = 5,
                tags = listOf("gen-2", "classified")
            ),
            budget = BigDecimal("5000000.00")
        )
    }
}