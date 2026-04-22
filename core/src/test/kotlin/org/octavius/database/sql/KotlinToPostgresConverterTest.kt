package org.octavius.database.sql

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.database.type.KotlinToPostgresConverter
import org.octavius.database.mapping.utils.createFakeTypeRegistry
import org.octavius.domain.test.pgtype.TestCategory
import org.octavius.domain.test.pgtype.TestMetadata
import org.octavius.domain.test.pgtype.TestPerson
import org.octavius.domain.test.pgtype.TestPriority
import org.octavius.domain.test.pgtype.TestProject
import org.octavius.domain.test.pgtype.TestStatus
import org.octavius.domain.test.pgtype.TestTask
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
            val sql = "SELECT * FROM users WHERE id = @id AND name = @name AND profile IS @profile"
            val params = mapOf("id" to 123, "name" to "John", "profile" to null)

            val result = converter.toPositionalQuery(sql, params)

            // Oczekujemy, że nazwane parametry zostaną zastąpione przez '?' z castami (poza null)
            Assertions.assertThat(result.sql).isEqualTo("SELECT * FROM users WHERE id = ?::int4 AND name = ?::text AND profile IS ?")

            // Oczekujemy listy wartości w kolejności występowania w SQL
            Assertions.assertThat(result.params).containsExactly(123, "John", null)
        }

        @Test
        fun `should convert enum to PGobject with correct snake_case_lower value`() {
            val sql = "SELECT * FROM tasks WHERE category = @category"
            val params = mapOf("category" to TestCategory.BugFix)

            val result = converter.toPositionalQuery(sql, params)

            Assertions.assertThat(result.sql).isEqualTo("SELECT * FROM tasks WHERE category = ?::public.test_category")
            Assertions.assertThat(result.params).hasSize(1)

            val pgObject = result.params[0] as PGobject
            Assertions.assertThat(pgObject.type).isEqualTo("text")
            Assertions.assertThat(pgObject.value).isEqualTo("bug_fix")
        }

        @Test
        fun `should convert JsonObject to jsonb PGobject`() {
            val sql = "UPDATE documents SET data = @data WHERE id = 1"
            val jsonData = Json.parseToJsonElement("""{"key": "value", "count": 100}""") as JsonObject
            val params = mapOf("data" to jsonData)

            val result = converter.toPositionalQuery(sql, params)

            Assertions.assertThat(result.sql).isEqualTo("UPDATE documents SET data = ?::jsonb WHERE id = 1")
            Assertions.assertThat(result.params).hasSize(1)

            val pgObject = result.params[0] as PGobject
            Assertions.assertThat(pgObject.type).isEqualTo("jsonb")
            Assertions.assertThat(pgObject.value).isEqualTo("""{"key":"value","count":100}""")
        }
    }

    @Nested
    inner class ArrayExpansion {

        @Test
        fun `should expand simple array into ARRAY literal`() {
            val sql = "SELECT * FROM users WHERE id = ANY(@ids)"
            val params = mapOf("ids" to listOf(10, 20, 30))

            val result = converter.toPositionalQuery(sql, params)

            // Oczekujemy ?::int4[] i PGobject z "{10,20,30}"
            Assertions.assertThat(result.sql).isEqualTo("SELECT * FROM users WHERE id = ANY(?::int4[])")
            Assertions.assertThat(result.params).hasSize(1)
            val pgObject = result.params[0] as PGobject
            Assertions.assertThat(pgObject.type).isEqualTo("text")
            Assertions.assertThat(pgObject.value).isEqualTo("{10,20,30}")
        }

        @Test
        fun `should handle empty arrays by converting to empty array literal`() {
            val sql = "SELECT * FROM users WHERE tags && @tags"
            val params = mapOf("tags" to emptyList<String>())

            val result = converter.toPositionalQuery(sql, params)

            // Teraz pusta tablica też jest parametrem
            Assertions.assertThat(result.sql).isEqualTo("SELECT * FROM users WHERE tags && ?::text[]")
            Assertions.assertThat(result.params).hasSize(1)
            val pgObject = result.params[0] as PGobject
            Assertions.assertThat(pgObject.type).isEqualTo("text")
            Assertions.assertThat(pgObject.value).isEqualTo("{}")
        }

        @Test
        fun `should expand array of enums correctly`() {
            val sql = "SELECT * FROM tasks WHERE status = ANY(@statuses)"
            val params = mapOf("statuses" to listOf(TestStatus.Active, TestStatus.Pending))

            val result = converter.toPositionalQuery(sql, params)

            Assertions.assertThat(result.sql).isEqualTo("SELECT * FROM tasks WHERE status = ANY(?::public.test_status[])")
            Assertions.assertThat(result.params).hasSize(1)

            val pgObject = result.params[0] as PGobject
            Assertions.assertThat(pgObject.type).isEqualTo("text")
            Assertions.assertThat(pgObject.value).isEqualTo("""{"active","pending"}""")
        }
    }

    @Nested
    inner class CompositeExpansion {

        @Test
        fun `should expand a single data class into ROW literal`() {
            val sql = "INSERT INTO employees (person) VALUES (@person)"
            val person = TestPerson("John Doe", 35, "john.doe@example.com", true, listOf("developer", "team-lead"))
            val params = mapOf("person" to person)

            val result = converter.toPositionalQuery(sql, params)

            // Oczekujemy ?::public.test_person i zserializowany literal
            Assertions.assertThat(result.sql).isEqualTo("INSERT INTO employees (person) VALUES (?::public.test_person)")

            Assertions.assertThat(result.params).hasSize(1)
            val pgObject = result.params[0] as PGobject
            Assertions.assertThat(pgObject.type).isEqualTo("text")
            // ( "John Doe", 35, "john.doe@example.com", t, "{"developer","team-lead"}" )
            Assertions.assertThat(pgObject.value).isEqualTo("""("John Doe",35,"john.doe@example.com",t,"{\"developer\",\"team-lead\"}")""")
        }

        @Test
        fun `should expand an array of data classes`() {
            val sql = "SELECT process_team(@team)"
            val team = listOf(
                TestPerson("Alice", 28, "a@a.com", true, listOf("frontend")),
                TestPerson("Bob", 42, "b@b.com", false, listOf("backend", "dba"))
            )
            val params = mapOf("team" to team)

            val result = converter.toPositionalQuery(sql, params)

            // Oczekujemy ?::test_person[]
            Assertions.assertThat(result.sql).isEqualTo("SELECT process_team(?::public.test_person[])")

            Assertions.assertThat(result.params).hasSize(1)
            val pgObject = result.params[0] as PGobject
            Assertions.assertThat(pgObject.type).isEqualTo("text")

            // Oczekujemy zakwotowanych kompozytów z potrójnie ucieczkowanymi cudzysłowami dla zagnieżdżonej listy
            val expectedValue = """{"(\"Alice\",28,\"a@a.com\",t,\"{\\\"frontend\\\"}\")","(\"Bob\",42,\"b@b.com\",f,\"{\\\"backend\\\",\\\"dba\\\"}\")"}"""
            Assertions.assertThat(pgObject.value).isEqualTo(expectedValue)
        }
    }

    @Nested
    inner class ComplexNestedStructureExpansion {
        @Test
        fun `should expand a deeply nested data class with all features`() {
            val sql = "SELECT update_project(@project_data)"
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
            val params = mapOf("project_data" to project)

            val result = converter.toPositionalQuery(sql, params)

            // Weryfikacja struktury SQL
            Assertions.assertThat(result.sql).isEqualTo("SELECT update_project(?::public.test_project)")

            // Weryfikacja parametrów
            Assertions.assertThat(result.params).hasSize(1)
            val pgObject = result.params[0] as PGobject
            Assertions.assertThat(pgObject.type).isEqualTo("text")

            val value = pgObject.value!!
            Assertions.assertThat(value).contains("Enterprise \\\"Fusion\\\" Project")
            Assertions.assertThat(value).contains("250000.75")
            Assertions.assertThat(value).contains("16.5")
            Assertions.assertThat(value).contains("active")
            Assertions.assertThat(value).contains("high")
        }
    }
}