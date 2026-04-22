package org.octavius.database.sql

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.data.builder.toFieldStrict
import org.octavius.data.getOrThrow
import org.octavius.database.AbstractIntegrationTest

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsonOperatorIntegrationTest: AbstractIntegrationTest() {

    override val sqlToExecuteOnSetup: String = """
        CREATE TABLE IF NOT EXISTS json_test (
            id SERIAL PRIMARY KEY,
            data JSONB
        );

        INSERT INTO json_test (data) VALUES ('{"a": 1, "b": 2}');
        INSERT INTO json_test (data) VALUES ('{"b": 2, "c": 3}');
        INSERT INTO json_test (data) VALUES ('{"d": 4}');
    """.trimIndent()

    @Test
    fun `should handle jsonb exist operator '?'`() {
        // This query contains '?' which JDBC might mistake for a parameter
        val result = dataAccess.rawQuery("SELECT count(*) as count FROM json_test WHERE data ? 'a'")
            .toFieldStrict<Long>()
            .getOrThrow()

        Assertions.assertThat(result).isEqualTo(1L)
    }

    @Test
    fun `should handle jsonb exist any operator '?|'`() {
        val result = dataAccess.rawQuery("SELECT count(*) as count FROM json_test WHERE data ?| array['a', 'c']")
            .toFieldStrict<Long>()
            .getOrThrow()

        // Rows: {"a": 1, "b": 2} (has a), {"b": 2, "c": 3} (has c)
        Assertions.assertThat(result).isEqualTo(2L)
    }

    @Test
    fun `should handle jsonb exist all operator '?&'`() {
        val result = dataAccess.rawQuery("SELECT count(*) as count FROM json_test WHERE data ?& array['a', 'b']")
            .toFieldStrict<Long>()
            .getOrThrow()

        // Rows: {"a": 1, "b": 2} (has a and b)
        Assertions.assertThat(result).isEqualTo(1L)
    }

    @Test
    fun `should handle mixing jsonb operators with named parameters`() {
        val result = dataAccess.rawQuery("SELECT count(*) as count FROM json_test WHERE data ? @key AND id = @id")
            .toFieldStrict<Long>("key" to "a", "id" to 1)
            .getOrThrow()

        Assertions.assertThat(result).isEqualTo(1L)
    }
}