package org.octavius.database.type.pgtype

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.database.type.KotlinToPostgresConverter
import org.octavius.database.type.utils.createFakeTypeRegistry
import org.octavius.domain.test.pgtype.*
import org.postgresql.util.PGobject
import java.math.BigDecimal

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KotlinToPostgresConverterTest {

    private val typeRegistry = createFakeTypeRegistry()
    private val converter = KotlinToPostgresConverter(typeRegistry)

    @Nested
    inner class SimpleTypeExpansion {

        @Test
        fun `should replace simple parameters with question marks and preserve values`() {
            val sql = "SELECT * FROM users WHERE id = :id AND name = :name AND profile IS :profile"
            val params = mapOf("id" to 123, "name" to "John", "profile" to null)

            val result = converter.expandParametersInQuery(sql, params)

            // Oczekujemy, że nazwane parametry zostaną zastąpione przez '?'
            assertThat(result.sql).isEqualTo("SELECT * FROM users WHERE id = ? AND name = ? AND profile IS ?")

            // Oczekujemy listy wartości w kolejności występowania w SQL
            assertThat(result.params).containsExactly(123, "John", null)
        }

        @Test
        fun `should convert enum to PGobject with correct snake_case_lower value`() {
            val sql = "SELECT * FROM tasks WHERE category = :category"
            val params = mapOf("category" to TestCategory.BugFix)

            val result = converter.expandParametersInQuery(sql, params)

            assertThat(result.sql).isEqualTo("SELECT * FROM tasks WHERE category = ?")
            assertThat(result.params).hasSize(1)

            val pgObject = result.params[0] as PGobject
            assertThat(pgObject.type).isEqualTo("test_category")
            assertThat(pgObject.value).isEqualTo("bug_fix")
        }

        @Test
        fun `should convert JsonObject to jsonb PGobject`() {
            val sql = "UPDATE documents SET data = :data WHERE id = 1"
            val jsonData = Json.parseToJsonElement("""{"key": "value", "count": 100}""") as JsonObject
            val params = mapOf("data" to jsonData)

            val result = converter.expandParametersInQuery(sql, params)

            assertThat(result.sql).isEqualTo("UPDATE documents SET data = ? WHERE id = 1")
            assertThat(result.params).hasSize(1)

            val pgObject = result.params[0] as PGobject
            assertThat(pgObject.type).isEqualTo("jsonb")
            assertThat(pgObject.value).isEqualTo("""{"key":"value","count":100}""")
        }
    }

    @Nested
    inner class ArrayExpansion {

        @Test
        fun `should expand simple array into ARRAY syntax`() {
            val sql = "SELECT * FROM users WHERE id = ANY(:ids)"
            val params = mapOf("ids" to listOf(10, 20, 30))

            val result = converter.expandParametersInQuery(sql, params)

            // Oczekujemy ARRAY[?, ?, ?]
            assertThat(result.sql).isEqualTo("SELECT * FROM users WHERE id = ANY(ARRAY[?, ?, ?])")
            assertThat(result.params).containsExactly(10, 20, 30)
        }

        @Test
        fun `should handle empty arrays by converting to empty array literal`() {
            val sql = "SELECT * FROM users WHERE tags && :tags"
            val params = mapOf("tags" to emptyList<String>())

            val result = converter.expandParametersInQuery(sql, params)

            // Pusta tablica to '{}' string
            assertThat(result.sql).isEqualTo("SELECT * FROM users WHERE tags && '{}'")
            assertThat(result.params).isEmpty()
        }

        @Test
        fun `should expand array of enums correctly`() {
            val sql = "SELECT * FROM tasks WHERE status = ANY(:statuses)"
            val params = mapOf("statuses" to listOf(TestStatus.Active, TestStatus.Pending))

            val result = converter.expandParametersInQuery(sql, params)

            assertThat(result.sql).isEqualTo("SELECT * FROM tasks WHERE status = ANY(ARRAY[?, ?])")
            assertThat(result.params).hasSize(2)

            val status1 = result.params[0] as PGobject
            assertThat(status1.type).isEqualTo("test_status")
            assertThat(status1.value).isEqualTo("active")

            val status2 = result.params[1] as PGobject
            assertThat(status2.type).isEqualTo("test_status")
            assertThat(status2.value).isEqualTo("pending")
        }
    }

    @Nested
    inner class CompositeExpansion {

        @Test
        fun `should expand a single data class into ROW syntax with type cast`() {
            val sql = "INSERT INTO employees (person) VALUES (:person)"
            val person = TestPerson("John Doe", 35, "john.doe@example.com", true, listOf("developer", "team-lead"))
            val params = mapOf("person" to person)

            val result = converter.expandParametersInQuery(sql, params)

            // ROW(?, ?, ?, ?, ARRAY[?, ?])::test_person
            assertThat(result.sql).isEqualTo("INSERT INTO employees (person) VALUES (ROW(?, ?, ?, ?, ARRAY[?, ?])::test_person)")

            assertThat(result.params).containsExactly(
                "John Doe",
                35,
                "john.doe@example.com",
                true,
                "developer",
                "team-lead"
            )
        }

        @Test
        fun `should expand an array of data classes`() {
            val sql = "SELECT process_team(:team)"
            val team = listOf(
                TestPerson("Alice", 28, "a@a.com", true, listOf("frontend")),
                TestPerson("Bob", 42, "b@b.com", false, listOf("backend", "dba"))
            )
            val params = mapOf("team" to team)

            val result = converter.expandParametersInQuery(sql, params)

            // ARRAY[ROW(...)::type, ROW(...)::type]
            val rowStructure = "ROW(?, ?, ?, ?, ARRAY[?])::test_person"
            val rowStructure2 = "ROW(?, ?, ?, ?, ARRAY[?, ?])::test_person"
            assertThat(result.sql).isEqualTo("SELECT process_team(ARRAY[$rowStructure, $rowStructure2])")

            assertThat(result.params).containsExactly(
                // Alice
                "Alice", 28, "a@a.com", true, "frontend",
                // Bob
                "Bob", 42, "b@b.com", false, "backend", "dba"
            )
        }
    }

    @Nested
    inner class ComplexNestedStructureExpansion {
        @Test
        fun `should expand a deeply nested data class with all features`() {
            val sql = "SELECT update_project(:project_data)"
            val project = TestProject(
                name = "Enterprise \"Fusion\" Project",
                description = "A complex project.",
                status = TestStatus.Active,
                teamMembers = listOf(
                    TestPerson("Project Manager", 45, "pm@corp.com", true, listOf("management")),
                    TestPerson("Lead Developer", 38, "lead@corp.com", true, listOf("dev", "architecture"))
                ),
                tasks = listOf(
                    TestTask(
                        id = 101,
                        title = "Initial Setup",
                        description = "Setup dev environment.",
                        status = TestStatus.Active,
                        priority = TestPriority.High,
                        category = TestCategory.Enhancement,
                        assignee = TestPerson("DevOps", 32, "devops@corp.com", true, listOf("infra")),
                        metadata = TestMetadata(
                            createdAt = LocalDateTime(2024, 1, 1, 10, 0),
                            updatedAt = LocalDateTime(2024, 1, 1, 12, 0),
                            version = 1,
                            tags = listOf("setup", "ci-cd")
                        ),
                        subtasks = listOf("Install Docker", "Configure DB"),
                        estimatedHours = BigDecimal("16.5")
                    )
                ),
                metadata = TestMetadata(
                    createdAt = LocalDateTime(2024, 1, 1, 9, 0),
                    updatedAt = LocalDateTime(2024, 1, 15, 18, 0),
                    version = 3,
                    tags = listOf("enterprise", "q1-2024")
                ),
                budget = BigDecimal("250000.75")
            )
            // Uproszczony obiekt do testu struktury, żeby nie pisać 50 asercji
            val params = mapOf("project_data" to project)

            val result = converter.expandParametersInQuery(sql, params)

            // Weryfikacja struktury SQL
            assertThat(result.sql).startsWith("SELECT update_project(ROW(")
            assertThat(result.sql).contains("ROW(", "ARRAY[", "::test_project", "::test_person")

            // Weryfikacja czy parametry trafiły na listę (sprawdzamy wybrane, charakterystyczne wartości)
            assertThat(result.params).contains(
                "Enterprise \"Fusion\" Project", // nazwa projektu
                BigDecimal("250000.75"), // budżet
                BigDecimal("16.5") // godziny zadania
            )

            // Weryfikacja czy Enumy są obiektami PGobject
            val pgObjects = result.params.filterIsInstance<PGobject>()
            assertThat(pgObjects).anySatisfy {
                assertThat(it.type).isEqualTo("test_status")
                assertThat(it.value).isEqualTo("active")
            }
            assertThat(pgObjects).anySatisfy {
                assertThat(it.type).isEqualTo("test_priority")
                assertThat(it.value).isEqualTo("high")
            }
        }
    }
}