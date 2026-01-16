package org.octavius.database.type.pgtype

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.octavius.database.type.PostgresToKotlinConverter
import org.octavius.database.type.utils.createFakeTypeRegistry
import org.octavius.domain.test.pgtype.*
import java.math.BigDecimal
import java.util.*
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Błyskawiczne testy jednostkowe dla PostgresToKotlinConverter.
 *
 * Używa "złotych" stringów i sztucznie stworzonego TypeRegistry,
 * dzięki czemu nie wymaga połączenia z bazą danych i testuje
 * wyłącznie logikę parsowania.
 */
class PostgresToKotlinConverterUnitTest {
    companion object {
        const val GOLDEN_STRING_ID = "1"
        const val GOLDEN_STRING_SIMPLE_TEXT = "Test \"quoted\" text with special chars: ąćęłńóśźż"
        const val GOLDEN_STRING_SIMPLE_NUMBER = "42"
        const val GOLDEN_STRING_SIMPLE_BOOL = "t"
        const val GOLDEN_STRING_SIMPLE_JSON = "{\"name\": \"test\", \"array\": [1, 2, 3], \"value\": 123, \"nested\": {\"key\": \"value\"}}"
        const val GOLDEN_STRING_SIMPLE_UUID = "7b14b7bb-625c-408c-b5ff-ccd2233747dc"
        const val GOLDEN_STRING_SIMPLE_DATE = "2024-01-15"
        const val GOLDEN_STRING_SIMPLE_TIMESTAMP = "2024-01-15 14:30:00"
        const val GOLDEN_STRING_SIMPLE_TIMESTAMPTZ = "2024-01-15 14:30:00+01"
        const val GOLDEN_STRING_SIMPLE_NUMERIC = "98765.4321"
        const val GOLDEN_STRING_SIMPLE_INTERVAL = "03:25:10"
        const val GOLDEN_STRING_SINGLE_STATUS = "active"
        const val GOLDEN_STRING_STATUS_ARRAY = "{active,pending,not_started}"
        const val GOLDEN_STRING_USER_EMAIL = "valid.email@example.com"
        const val GOLDEN_STRING_ITEM_COUNT = "150"
        const val GOLDEN_STRING_TEXT_ARRAY = "{first,second,\"third with \\\"quotes\\\"\",\"fourth with ąćę\"}"
        const val GOLDEN_STRING_NUMBER_ARRAY = "{1,2,3,4,5}"
        const val GOLDEN_STRING_NESTED_TEXT_ARRAY = "{{a,b},{c,d},{\"e with \\\"quotes\\\"\",f}}"
        const val GOLDEN_STRING_SINGLE_PERSON = "(\"John \"\"The Developer\"\" Doe\",30,john@example.com,t,\"{admin,developer,team-lead}\")"
        const val GOLDEN_STRING_PERSON_ARRAY = "{\"(\\\"Alice Smith\\\",25,alice@example.com,t,\\\"{developer,frontend}\\\")\",\"(\\\"Bob \\\"\\\"Database\\\"\\\" Johnson\\\",35,bob@example.com,f,\\\"{dba,backend}\\\")\",\"(\\\"Carol \\\"\\\"The Tester\\\"\\\" Williams\\\",28,carol@example.com,t,\\\"{qa,automation}\\\")\"}"
        const val GOLDEN_STRING_PROJECT_DATA = "(\"Complex \"\"Enterprise\"\" Project\",\"A very complex project with all possible data types and \"\"special characters\"\"\",active,\"{\"\"(\\\\\"\"Project Manager\\\\\"\",40,pm@example.com,t,\\\\\"\"{manager,stakeholder}\\\\\"\")\"\",\"\"(\\\\\"\"Senior Dev \\\\\"\"\\\\\"\"The Architect\\\\\"\"\\\\\"\"\\\\\"\",35,senior@example.com,t,\\\\\"\"{architect,senior-dev}\\\\\"\")\"\",\"\"(\\\\\"\"Junior Dev\\\\\"\",24,junior@example.com,t,\\\\\"\"{junior-dev,learner}\\\\\"\")\"\",\"\"(,30,\\\\\"\"\\\\\"\",t,{user})\"\"}\",\"{\"\"(1,\\\\\"\"Setup \\\\\"\"\\\\\"\"Development\\\\\"\"\\\\\"\" Environment\\\\\"\",\\\\\"\"Configure all development tools and \\\\\"\"\\\\\"\"databases\\\\\"\"\\\\\"\"\\\\\"\",active,high,enhancement,\\\\\"\"(\\\\\"\"\\\\\"\"DevOps Guy\\\\\"\"\\\\\"\",32,devops@example.com,t,\\\\\"\"\\\\\"\"{devops,infrastructure}\\\\\"\"\\\\\"\")\\\\\"\",\\\\\"\"(\\\\\"\"\\\\\"\"2024-01-01 09:00:00\\\\\"\"\\\\\"\",\\\\\"\"\\\\\"\"2024-01-15 14:30:00\\\\\"\"\\\\\"\",1,\\\\\"\"\\\\\"\"{setup,infrastructure,priority}\\\\\"\"\\\\\"\")\\\\\"\",\\\\\"\"{\\\\\"\"\\\\\"\"install docker\\\\\"\"\\\\\"\",\\\\\"\"\\\\\"\"setup database\\\\\"\"\\\\\"\",\\\\\"\"\\\\\"\"configure \\\\\\\\\\\\\\\\\\\\\"\"\\\\\"\"CI/CD\\\\\\\\\\\\\\\\\\\\\"\"\\\\\"\"\\\\\"\"\\\\\"\"}\\\\\"\",16.5)\"\",\"\"(2,\\\\\"\"Implement \\\\\"\"\\\\\"\"Core\\\\\"\"\\\\\"\" Features\\\\\"\",\\\\\"\"Build the main functionality with proper \\\\\"\"\\\\\"\"error handling\\\\\"\"\\\\\"\"\\\\\"\",pending,critical,feature,\\\\\"\"(\\\\\"\"\\\\\"\"Lead Developer\\\\\"\"\\\\\"\",38,lead@example.com,t,\\\\\"\"\\\\\"\"{lead,full-stack}\\\\\"\"\\\\\"\")\\\\\"\",\\\\\"\"(\\\\\"\"\\\\\"\"2024-01-10 10:00:00\\\\\"\"\\\\\"\",\\\\\"\"\\\\\"\"2024-01-20 16:00:00\\\\\"\"\\\\\"\",2,\\\\\"\"\\\\\"\"{core,critical,feature}\\\\\"\"\\\\\"\")\\\\\"\",\\\\\"\"{\\\\\"\"\\\\\"\"design API\\\\\"\"\\\\\"\",\\\\\"\"\\\\\"\"implement \\\\\\\\\\\\\\\\\\\\\"\"\\\\\"\"business logic\\\\\\\\\\\\\\\\\\\\\"\"\\\\\"\"\\\\\"\"\\\\\"\",\\\\\"\"\\\\\"\"add tests\\\\\"\"\\\\\"\",\\\\\"\"\\\\\"\"write \\\\\\\\\\\\\\\\\\\\\"\"\\\\\"\"documentation\\\\\\\\\\\\\\\\\\\\\"\"\\\\\"\"\\\\\"\"\\\\\"\"}\\\\\"\",40.0)\"\"}\",\"(\"\"2024-01-01 08:00:00\"\",\"\"2024-01-15 14:30:00\"\",3,\"\"{enterprise,complex,multi-team,high-priority}\"\")\",150000.50)"
        const val GOLDEN_STRING_PROJECT_ARRAY = "{\"(\\\"Small \\\"\\\"Maintenance\\\"\\\" Project\\\",\\\"Quick fixes and \\\"\\\"minor improvements\\\"\\\"\\\",not_started,\\\"{\\\"\\\"(Maintainer,29,maintainer@example.com,t,{maintainer})\\\"\\\"}\\\",\\\"{\\\"\\\"(10,\\\\\\\\\\\"\\\"Fix \\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"Critical\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\" Bug\\\\\\\\\\\"\\\",\\\\\\\\\\\"\\\"Resolve the issue with \\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"data corruption\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\",pending,critical,bug_fix,\\\\\\\\\\\"\\\"(\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"Bug Hunter\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\",31,bughunter@example.com,t,{debugger})\\\\\\\\\\\"\\\",\\\\\\\\\\\"\\\"(\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"2024-01-20 09:00:00\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\",\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"2024-01-20 09:00:00\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\",1,\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"{bugfix,urgent}\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\")\\\\\\\\\\\"\\\",\\\\\\\\\\\"\\\"{\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"investigate issue\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\",\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"fix \\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"root cause\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\",\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"test solution\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"}\\\\\\\\\\\"\\\",8.0)\\\"\\\"}\\\",\\\"(\\\"\\\"2024-01-20 09:00:00\\\"\\\",\\\"\\\"2024-01-20 09:00:00\\\"\\\",1,\\\"\\\"{maintenance,bugfix}\\\"\\\")\\\",5000.00)\",\"(\\\"Research \\\"\\\"Innovation\\\"\\\" Project\\\",\\\"Experimental features, \\\"\\\"new technologies\\\"\\\"\\\",active,\\\"{\\\"\\\"(\\\\\\\\\\\"\\\"Researcher 'The Innovator'\\\\\\\\\\\"\\\",33,research@example.com,t,\\\\\\\\\\\"\\\"{researcher,innovator}\\\\\\\\\\\"\\\")\\\"\\\",\\\"\\\"(\\\\\\\\\\\"\\\"Data Scientist\\\\\\\\\\\"\\\",27,data@example.com,t,\\\\\\\\\\\"\\\"{data-science,ml}\\\\\\\\\\\"\\\")\\\"\\\"}\\\",\\\"{\\\"\\\"(20,\\\\\\\\\\\"\\\"Prototype \\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"AI\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\" Integration\\\\\\\\\\\"\\\",\\\\\\\\\\\"\\\"Build proof of concept for \\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"machine learning\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\" features\\\\\\\\\\\"\\\",active,medium,feature,\\\\\\\\\\\"\\\"(\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"AI Specialist\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\",30,ai@example.com,t,\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"{ai,ml,python}\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\")\\\\\\\\\\\"\\\",\\\\\\\\\\\"\\\"(\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"2024-01-05 10:00:00\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\",\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"2024-01-15 15:00:00\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\",5,\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"{ai,prototype,experimental}\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\")\\\\\\\\\\\"\\\",\\\\\\\\\\\"\\\"{\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"research \\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"algorithms\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\",\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"build model\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\",\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"integrate \\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"with backend\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\",\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"test \\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"accuracy\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"\\\\\\\\\\\"\\\"}\\\\\\\\\\\"\\\",60.0)\\\"\\\"}\\\",\\\"(\\\"\\\"2024-01-05 10:00:00\\\"\\\",\\\"\\\"2024-01-15 15:00:00\\\"\\\",5,\\\"\\\"{research,innovation,ai}\\\"\\\")\\\",75000.25)\"}"
    }
    private val fakeTypeRegistry = createFakeTypeRegistry()
    private val converter = PostgresToKotlinConverter(fakeTypeRegistry)

    @Test
    fun `should convert all standard types correctly`() {
        assertThat(converter.convert(GOLDEN_STRING_SIMPLE_TEXT, "text")).isEqualTo("Test \"quoted\" text with special chars: ąćęłńóśźż")
        assertThat(converter.convert(GOLDEN_STRING_SIMPLE_NUMBER, "int4")).isEqualTo(42)
        assertThat(converter.convert(GOLDEN_STRING_SIMPLE_BOOL, "bool")).isEqualTo(true)
        assertThat(converter.convert(GOLDEN_STRING_SIMPLE_JSON, "jsonb")).isEqualTo(Json.parseToJsonElement(GOLDEN_STRING_SIMPLE_JSON) as JsonObject)
        assertThat(converter.convert(GOLDEN_STRING_SIMPLE_UUID, "uuid")).isEqualTo(UUID.fromString("7b14b7bb-625c-408c-b5ff-ccd2233747dc"))
        assertThat(converter.convert(GOLDEN_STRING_SIMPLE_DATE, "date")).isEqualTo(LocalDate.parse("2024-01-15"))
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `should convert time standard types correctly`() {
        assertThat(converter.convert(GOLDEN_STRING_SIMPLE_TIMESTAMP, "timestamp")).isEqualTo(LocalDateTime.parse("2024-01-15T14:30:00"))
        assertThat(converter.convert(GOLDEN_STRING_SIMPLE_TIMESTAMPTZ, "timestamptz"))
            .isEqualTo(Instant.parse("2024-01-15T13:30:00Z"))
        assertThat(converter.convert(GOLDEN_STRING_SIMPLE_NUMERIC, "numeric"))
            .isEqualTo(BigDecimal("98765.4321"))
        assertThat(converter.convert(GOLDEN_STRING_SIMPLE_INTERVAL, "interval"))
            .isEqualTo(Duration.parse("PT3H25M10S"))
    }

    @Test
    fun `should convert all enum types correctly`() {
        assertThat(converter.convert(GOLDEN_STRING_SINGLE_STATUS, "test_status")).isEqualTo(TestStatus.Active)
        assertThat(converter.convert(GOLDEN_STRING_STATUS_ARRAY, "_test_status")).isEqualTo(listOf(TestStatus.Active, TestStatus.Pending, TestStatus.NotStarted))
    }

    @Test
    fun `should convert all simple array types correctly`() {
        assertThat(converter.convert(GOLDEN_STRING_TEXT_ARRAY, "_text")).isEqualTo(listOf("first", "second", "third with \"quotes\"", "fourth with ąćę"))
        assertThat(converter.convert(GOLDEN_STRING_NUMBER_ARRAY, "_int4")).isEqualTo(listOf(1, 2, 3, 4, 5))
        assertThat(converter.convert(GOLDEN_STRING_NESTED_TEXT_ARRAY, "_text")).isEqualTo(listOf(listOf("a", "b"), listOf("c", "d"), listOf("e with \"quotes\"", "f")))
    }

    @Test
    fun `should convert composite person type`() {
        val expected = TestPerson(
            name = "John \"The Developer\" Doe",
            age = 30,
            email = "john@example.com",
            active = true,
            roles = listOf("admin", "developer", "team-lead")
        )
        val result = converter.convert(GOLDEN_STRING_SINGLE_PERSON, "test_person")
        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `should convert an array of composite persons`() {
        val expected = listOf(
            TestPerson("Alice Smith", 25, "alice@example.com", true, listOf("developer", "frontend")),
            TestPerson("Bob \"Database\" Johnson", 35, "bob@example.com", false, listOf("dba", "backend")),
            TestPerson("Carol \"The Tester\" Williams", 28, "carol@example.com", true, listOf("qa", "automation"))
        )
        val result = converter.convert(GOLDEN_STRING_PERSON_ARRAY, "_test_person")
        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `should convert deeply nested project composite`() {
        val expected = TestProject(
            name = "Complex \"Enterprise\" Project",
            description = "A very complex project with all possible data types and \"special characters\"",
            status = TestStatus.Active,
            teamMembers = listOf(
                TestPerson("Project Manager", 40, "pm@example.com", true, listOf("manager", "stakeholder")),
                TestPerson(
                    "Senior Dev \"The Architect\"",
                    35,
                    "senior@example.com",
                    true,
                    listOf("architect", "senior-dev")
                ),
                TestPerson("Junior Dev", 24, "junior@example.com", true, listOf("junior-dev", "learner")),
                TestPerson(null, 30, "", true, listOf("user"))
            ),
            tasks = listOf(
                TestTask(
                    id = 1,
                    title = "Setup \"Development\" Environment",
                    description = "Configure all development tools and \"databases\"",
                    status = TestStatus.Active,
                    priority = TestPriority.High,
                    category = TestCategory.Enhancement,
                    assignee = TestPerson(
                        "DevOps Guy",
                        32,
                        "devops@example.com",
                        true,
                        listOf("devops", "infrastructure")
                    ),
                    metadata = TestMetadata(
                        createdAt = LocalDateTime.parse("2024-01-01T09:00:00"),
                        updatedAt = LocalDateTime.parse("2024-01-15T14:30:00"),
                        version = 1,
                        tags = listOf("setup", "infrastructure", "priority")
                    ),
                    subtasks = listOf("install docker", "setup database", "configure \"CI/CD\""),
                    estimatedHours = BigDecimal("16.5")
                ),
                TestTask(
                    id = 2,
                    title = "Implement \"Core\" Features",
                    description = "Build the main functionality with proper \"error handling\"",
                    status = TestStatus.Pending,
                    priority = TestPriority.Critical,
                    category = TestCategory.Feature,
                    assignee = TestPerson("Lead Developer", 38, "lead@example.com", true, listOf("lead", "full-stack")),
                    metadata = TestMetadata(
                        createdAt = LocalDateTime.parse("2024-01-10T10:00:00"),
                        updatedAt = LocalDateTime.parse("2024-01-20T16:00:00"),
                        version = 2,
                        tags = listOf("core", "critical", "feature")
                    ),
                    subtasks = listOf(
                        "design API",
                        "implement \"business logic\"",
                        "add tests",
                        "write \"documentation\""
                    ),
                    estimatedHours = BigDecimal("40.0")
                )
            ),
            metadata = TestMetadata(
                createdAt = LocalDateTime.parse("2024-01-01T08:00:00"),
                updatedAt = LocalDateTime.parse("2024-01-15T14:30:00"),
                version = 3,
                tags = listOf("enterprise", "complex", "multi-team", "high-priority")
            ),
            budget = BigDecimal("150000.50")
        )
        val result = converter.convert(GOLDEN_STRING_PROJECT_DATA, "test_project")
        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `should convert an array of deeply nested projects`() {
        // Obiekt expected dla tego testu byłby gigantyczny, więc dla czytelności
        // sprawdzimy tylko kilka kluczowych pól, ale nadal porównując całe obiekty.
        val result = converter.convert(GOLDEN_STRING_PROJECT_ARRAY, "_test_project") as List<TestProject>

        assertThat(result).hasSize(2)
        // Porównajmy pierwszego całego taska w pierwszym projekcie
        val expectedFirstTask = TestTask(
            id = 10,
            title = "Fix \"Critical\" Bug",
            description = "Resolve the issue with \"data corruption\"",
            status = TestStatus.Pending,
            priority = TestPriority.Critical,
            category = TestCategory.BugFix,
            assignee = TestPerson("Bug Hunter", 31, "bughunter@example.com", true, listOf("debugger")),
            metadata = TestMetadata(
                createdAt = LocalDateTime.parse("2024-01-20T09:00:00"),
                updatedAt = LocalDateTime.parse("2024-01-20T09:00:00"),
                version = 1,
                tags = listOf("bugfix", "urgent")
            ),
            subtasks = listOf("investigate issue", "fix \"root cause\"", "test solution"),
            estimatedHours = BigDecimal("8.0")
        )
        assertThat(result[0].tasks[0]).isEqualTo(expectedFirstTask)

        // Sprawdźmy kluczowe pole w drugim projekcie
        assertThat(result[1].name).isEqualTo("Research \"Innovation\" Project")
        assertThat(result[1].teamMembers[0].name).isEqualTo("Researcher 'The Innovator'")
    }

}