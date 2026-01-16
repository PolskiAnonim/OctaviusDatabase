package org.octavius.database.type.pgtype

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.database.RowMappers
import org.octavius.database.config.DatabaseConfig
import org.octavius.database.type.ResultSetValueExtractor
import org.octavius.database.type.registry.TypeRegistry
import org.octavius.database.type.registry.TypeRegistryLoader
import org.octavius.domain.test.pgtype.TestPerson
import org.octavius.domain.test.pgtype.TestPriority
import org.octavius.domain.test.pgtype.TestProject
import org.octavius.domain.test.pgtype.TestStatus
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Paths

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealPostgresDataTest {

    // Te pola będą dostępne we wszystkich testach w tej klasie
    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var typeRegistry: TypeRegistry

    @BeforeAll
    fun setup() {
        // 1. Ładujemy konfigurację
        val databaseConfig = DatabaseConfig.loadFromFile("test-database.properties")

        // 2. KRYTYCZNE ZABEZPIECZENIE (ASSERTION GUARD)
        val connectionUrl = databaseConfig.dbUrl
        val dbName = connectionUrl.substringAfterLast("/") // Wyciągamy nazwę bazy z URL-a

        // Sprawdzamy zarówno URL, jak i nazwę bazy, aby być podwójnie pewnym.
        // Można też sprawdzić hosta, port etc.
        if (!connectionUrl.contains("localhost:5432") || dbName != "octavius_test") {
            throw IllegalStateException(
                "ABORTING TEST! Attempting to run destructive tests on a non-test database. " +
                        "Connection URL: '$connectionUrl'. This is a safety guard to prevent data loss."
            )
        }
        println("Safety guard passed. Connected to the correct test database: $dbName")

        // 2. Tworzymy DataSource i JdbcTemplate
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = databaseConfig.dbUrl
            username = databaseConfig.dbUsername
            password = databaseConfig.dbPassword
        }
        val dataSource = HikariDataSource(hikariConfig)
        jdbcTemplate = JdbcTemplate(dataSource)

        // 3. Wrzucamy skrypt testowy do bazy DOKŁADNIE RAZ
        try {
            // Najpierw usuwamy stary schemat, żeby mieć pewność czystego startu
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS public CASCADE;")
            jdbcTemplate.execute("CREATE SCHEMA public;")

            // Wczytujemy i wykonujemy cały skrypt SQL (łącznie z INSERT)
            val initSql = String(
                Files.readAllBytes(
                    Paths.get(
                        this::class.java.classLoader.getResource("init-complex-test-db.sql")!!.toURI()
                    )
                )
            )
            jdbcTemplate.execute(initSql)
            println("Complex test DB schema and data initialized successfully.")
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }

        // 4. Inicjalizujemy zależności dla konwerterów
        val loader = TypeRegistryLoader(
            jdbcTemplate,
            listOf("org.octavius.domain.test.pgtype"),
            databaseConfig.dbSchemas
        )
        typeRegistry = runBlocking {
            loader.load()
        }
    }

    // Nie potrzebujemy @BeforeEach, bo tylko czytamy dane!

    @Test
    fun `should convert a row with complex types into a map of correct Kotlin objects`() {
        // Given: dane są już w bazie dzięki setupowi

        // When: Używamy RowMappers (które używają konwertera) do pobrania danych
        val result: Map<String, Any?> = jdbcTemplate.queryForObject(
            "SELECT * FROM complex_test_data WHERE id = 1",
            RowMappers(ResultSetValueExtractor(typeRegistry)).ColumnNameMapper()
        )

// Then: Sprawdzamy każdy przekonwertowany obiekt
        assertThat(result["simple_text"]).isEqualTo("Test \"quoted\" text with special chars: ąćęłńóśźż")
        assertThat(result["simple_bool"]).isEqualTo(true)
        assertThat(result["single_status"]).isEqualTo(TestStatus.Active)

        // Sprawdzamy tablicę enumów
        assertThat(result["status_array"] as List<*>).containsExactly(
            TestStatus.Active,
            TestStatus.Pending,
            TestStatus.NotStarted
        )

        // Sprawdzamy pojedynczy kompozyt
        val person = result["single_person"] as TestPerson
        assertThat(person.name).isEqualTo("John \"The Developer\" Doe")
        assertThat(person.age).isEqualTo(30)
        assertThat(person.roles).containsExactly("admin", "developer", "team-lead")

        // Sprawdzamy "mega" kompozyt - projekt
        val project = result["project_data"] as TestProject
        assertThat(project.name).isEqualTo("Complex \"Enterprise\" Project")
        assertThat(project.status).isEqualTo(TestStatus.Active)
        assertThat(project.teamMembers).hasSize(4)
        assertThat(project.teamMembers[0].name).isEqualTo("Project Manager")
        assertThat(project.teamMembers[3].name).isEqualTo(null)

        val firstTask = project.tasks[0]
        assertThat(firstTask.title).isEqualTo("Setup \"Development\" Environment")
        assertThat(firstTask.priority).isEqualTo(TestPriority.High)
        assertThat(firstTask.assignee.name).isEqualTo("DevOps Guy")
        assertThat(firstTask.metadata.tags).containsExactly("setup", "infrastructure", "priority")

        // Sprawdzamy tablicę projektów (zagnieżdżenie do potęgi)
        val projectArray = result["project_array"] as List<TestProject>
        assertThat(projectArray).hasSize(2)
        assertThat(projectArray[0].name).isEqualTo("Small \"Maintenance\" Project")
        assertThat(projectArray[1].tasks[0].assignee.name).isEqualTo("AI Specialist")
    }
}