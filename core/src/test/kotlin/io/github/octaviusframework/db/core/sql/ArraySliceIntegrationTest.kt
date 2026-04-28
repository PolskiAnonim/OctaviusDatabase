package io.github.octaviusframework.db.core.sql

import io.github.octaviusframework.db.api.builder.toSingleStrict
import io.github.octaviusframework.db.api.getOrThrow
import io.github.octaviusframework.db.core.AbstractIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ArraySliceIntegrationTest : AbstractIntegrationTest() {

    override val sqlToExecuteOnSetup: String = """
        CREATE TABLE array_test_table (
            id SERIAL PRIMARY KEY,
            int_array INT[],
            index INT
        );

        INSERT INTO array_test_table (int_array, index) VALUES (ARRAY[10, 20, 30, 40, 50], 3);
    """.trimIndent()

    @Test
    fun `should handle array index access with parameter`() {
        // SELECT int_array[@idx] FROM array_test_table WHERE id = 1
        val result = dataAccess.rawQuery("SELECT int_array[@idx] as val FROM array_test_table WHERE id = 1")
            .toSingleStrict(mapOf("idx" to 2))
            .getOrThrow()

        assertThat(result["val"] as Int).isEqualTo(20)
    }

    @Test
    fun `should handle array slice with two parameters`() {
        // SELECT int_array[@start:@end] FROM array_test_table WHERE id = 1
        val result = dataAccess.rawQuery("SELECT int_array[@start:@end] as slice FROM array_test_table WHERE id = 1")
            .toSingleStrict(mapOf("start" to 2, "end" to 4))
            .getOrThrow()

        assertThat(result["slice"] as List<*>).containsExactly(20, 30, 40)
    }

    @Test
    fun `should handle array slice with only upper bound parameter`() {
        // SELECT int_array[:@end] FROM array_test_table WHERE id = 1
        val result = dataAccess.rawQuery("SELECT int_array[:@end] as slice FROM array_test_table WHERE id = 1")
            .toSingleStrict(mapOf("end" to 3))
            .getOrThrow()

        assertThat(result["slice"] as List<*>).containsExactly(10, 20, 30)
    }

    @Test
    fun `should handle array slice with only lower bound parameter`() {
        // SELECT int_array[:index] FROM array_test_table WHERE id = 1
        val result =
            dataAccess.rawQuery("SELECT int_array[:index] as slice, @index as idx  FROM array_test_table WHERE id = 1")
                .toSingleStrict("index" to 1)
                .getOrThrow()

        assertThat(result["slice"] as List<*>).containsExactly(10, 20, 30)
        assertThat(result["idx"] as Int).isEqualTo(1)
    }

    @Test
    fun `should handle complex array slice with expressions and parameters`() {
        // SELECT int_array[@off + 1 : @limit * 2] FROM array_test_table WHERE id = 1
        // (1 + 1 : 2 * 2) -> (2 : 4)
        val result =
            dataAccess.rawQuery("SELECT int_array[@off + 1 : @limit * 2] as slice FROM array_test_table WHERE id = 1")
                .toSingleStrict(mapOf("off" to 1, "limit" to 2))
                .getOrThrow()

        assertThat(result["slice"] as List<*>).containsExactly(20, 30, 40)
    }
}