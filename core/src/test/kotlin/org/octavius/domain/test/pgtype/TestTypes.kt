package org.octavius.domain.test.pgtype

import kotlinx.datetime.LocalDateTime
import org.octavius.data.annotation.PgComposite
import org.octavius.data.annotation.PgEnum
import org.octavius.data.util.CaseConvention
import java.math.BigDecimal

@PgEnum(pgConvention = CaseConvention.SNAKE_CASE_LOWER)
enum class TestStatus { Active, Inactive, Pending, NotStarted }
@PgEnum(pgConvention = CaseConvention.SNAKE_CASE_LOWER)
enum class TestPriority { Low, Medium, High, Critical }
@PgEnum(pgConvention = CaseConvention.SNAKE_CASE_LOWER)
enum class TestCategory { BugFix, Feature, Enhancement, Documentation }
@PgComposite
data class TestMetadata(
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val version: Int,
    val tags: List<String>
)

// W Postgresie wszystkie pola są nullable
// Jednakże tutaj ? dodane jest tylko dla pola które faktycznie będzie nullem
@PgComposite
data class TestPerson(
    val name: String?,
    val age: Int,
    val email: String,
    val active: Boolean,
    val roles: List<String>
)

@PgComposite
data class TestTask(
    val id: Int,
    val title: String,
    val description: String,
    val status: TestStatus,
    val priority: TestPriority,
    val category: TestCategory,
    val assignee: TestPerson,
    val metadata: TestMetadata,
    val subtasks: List<String>,
    val estimatedHours: BigDecimal
)

@PgComposite
data class TestProject(
    val name: String,
    val description: String,
    val status: TestStatus,
    val teamMembers: List<TestPerson>,
    val tasks: List<TestTask>,
    val metadata: TestMetadata,
    val budget: BigDecimal
)
