package org.octavius.database.advanced

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.data.builder.toSingleStrict
import org.octavius.data.getOrThrow
import org.octavius.data.type.withPgType
import org.octavius.database.AbstractIntegrationTest

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProcedureIntegrationTest: AbstractIntegrationTest() {

    override val sqlToExecuteOnSetup: String = """
        CREATE OR REPLACE PROCEDURE test_out_proc(IN val_in INT, OUT val_out TEXT)
            LANGUAGE plpgsql AS $$
            BEGIN
                val_out := 'Result: ' || val_in;
            END;
        $$;
    """.trimIndent()

    @Test
    fun `should call procedure with OUT parameter using NULL cast`() {
        val result = dataAccess.rawQuery("CALL test_out_proc(@val_in, NULL::text)")
            .toSingleStrict("val_in" to 42)
            .getOrThrow()

        Assertions.assertEquals("Result: 42", result["val_out"])
    }

    @Test
    fun `should call procedure with OUT parameter using PgTyped placeholder`() {
        val result = dataAccess.rawQuery("CALL test_out_proc(@val_in, @val_out)")
            .toSingleStrict(
                "val_in" to 10,
                "val_out" to null.withPgType("text")
            )
            .getOrThrow()

        Assertions.assertEquals("Result: 10", result["val_out"])
    }
}