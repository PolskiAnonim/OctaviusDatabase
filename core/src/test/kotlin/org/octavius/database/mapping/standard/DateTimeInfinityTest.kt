package org.octavius.database.mapping.standard

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.data.builder.execute
import org.octavius.data.builder.toField
import org.octavius.data.getOrThrow
import org.octavius.data.type.DISTANT_FUTURE
import org.octavius.data.type.DISTANT_PAST
import org.octavius.database.AbstractIntegrationTest
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

        Assertions.assertEquals(LocalDate.DISTANT_FUTURE, result1)
        Assertions.assertEquals(LocalDate.DISTANT_PAST, result2)
    }

    @Test
    fun `should map infinity values correctly for LocalDateTime`() {
        dataAccess.rawQuery("INSERT INTO infinity_test (id, ts) VALUES (3, @plus_infinity), (4, @minus_infinity)").execute("plus_infinity" to LocalDateTime.DISTANT_FUTURE, "minus_infinity" to LocalDateTime.DISTANT_PAST).getOrThrow()

        val result1 = dataAccess.select("ts").from("infinity_test").where("id = 3").toField<LocalDateTime>().getOrThrow()
        val result2 = dataAccess.select("ts").from("infinity_test").where("id = 4").toField<LocalDateTime>().getOrThrow()

        Assertions.assertEquals(LocalDateTime.DISTANT_FUTURE, result1)
        Assertions.assertEquals(LocalDateTime.DISTANT_PAST, result2)
    }

    @Test
    fun `should map infinity values correctly for Instant`() {
        dataAccess.rawQuery("INSERT INTO infinity_test (id, tstz) VALUES (5, @plus_infinity), (6, @minus_infinity)").execute("plus_infinity" to Instant.DISTANT_FUTURE, "minus_infinity" to Instant.DISTANT_PAST).getOrThrow()

        val result1 = dataAccess.select("tstz").from("infinity_test").where("id = 5").toField<Instant>().getOrThrow()
        val result2 = dataAccess.select("tstz").from("infinity_test").where("id = 6").toField<Instant>().getOrThrow()

        Assertions.assertEquals(Instant.DISTANT_FUTURE, result1, "For infinity")
        Assertions.assertEquals(Instant.DISTANT_PAST, result2, "For -infinity")
    }
}