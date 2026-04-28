package io.github.octaviusframework.db.core.mapping.standard

import io.github.octaviusframework.db.api.builder.execute
import io.github.octaviusframework.db.api.builder.toField
import io.github.octaviusframework.db.api.getOrThrow
import io.github.octaviusframework.db.api.type.DISTANT_FUTURE
import io.github.octaviusframework.db.api.type.DISTANT_PAST
import io.github.octaviusframework.db.core.AbstractIntegrationTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DateTimeInfinityTest: AbstractIntegrationTest() {

    override val sqlToExecuteOnSetup: String = """
        CREATE TABLE infinity_test (
                id SERIAL PRIMARY KEY,
                d DATE,
                ts TIMESTAMP,
                tstz TIMESTAMPTZ
        );
    """.trimIndent()

    @Test
    fun `should map infinity values correctly for LocalDate`() {
        dataAccess.rawQuery("INSERT INTO infinity_test (id, d) VALUES (1, @plus_infinity), (2, @minus_infinity)").execute("plus_infinity" to LocalDate.DISTANT_FUTURE, "minus_infinity" to LocalDate.DISTANT_PAST).getOrThrow()

        val result1 = dataAccess.select("d").from("infinity_test").where("id = 1").toField<LocalDate>().getOrThrow()
        val result2 = dataAccess.select("d").from("infinity_test").where("id = 2").toField<LocalDate>().getOrThrow()

        assertEquals(LocalDate.DISTANT_FUTURE, result1)
        assertEquals(LocalDate.DISTANT_PAST, result2)
    }

    @Test
    fun `should map infinity values correctly for LocalDateTime`() {
        dataAccess.rawQuery("INSERT INTO infinity_test (id, ts) VALUES (3, @plus_infinity), (4, @minus_infinity)").execute("plus_infinity" to LocalDateTime.DISTANT_FUTURE, "minus_infinity" to LocalDateTime.DISTANT_PAST).getOrThrow()

        val result1 = dataAccess.select("ts").from("infinity_test").where("id = 3").toField<LocalDateTime>().getOrThrow()
        val result2 = dataAccess.select("ts").from("infinity_test").where("id = 4").toField<LocalDateTime>().getOrThrow()

        assertEquals(LocalDateTime.DISTANT_FUTURE, result1)
        assertEquals(LocalDateTime.DISTANT_PAST, result2)
    }

    @Test
    fun `should map infinity values correctly for Instant`() {
        dataAccess.rawQuery("INSERT INTO infinity_test (id, tstz) VALUES (5, @plus_infinity), (6, @minus_infinity)").execute("plus_infinity" to Instant.DISTANT_FUTURE, "minus_infinity" to Instant.DISTANT_PAST).getOrThrow()

        val result1 = dataAccess.select("tstz").from("infinity_test").where("id = 5").toField<Instant>().getOrThrow()
        val result2 = dataAccess.select("tstz").from("infinity_test").where("id = 6").toField<Instant>().getOrThrow()

        assertEquals(Instant.DISTANT_FUTURE, result1, "For infinity")
        assertEquals(Instant.DISTANT_PAST, result2, "For -infinity")
    }
}