package io.github.octaviusframework.db.core.exception

import io.github.octaviusframework.db.api.DataResult
import io.github.octaviusframework.db.api.exception.StatementException
import io.github.octaviusframework.db.api.exception.StatementExceptionMessage
import io.github.octaviusframework.db.core.AbstractIntegrationTest
import org.assertj.core.api.Assertions.assertThat

import org.junit.jupiter.api.Test

class StatementIntegrationTest: AbstractIntegrationTest() {

    @Test
    fun `should return OBJECT_NOT_FOUND for non-existent table`() {
        val result = dataAccess.rawQuery("SELECT * FROM table_that_does_not_exist").execute()
        
        assertThat(result).isInstanceOf(DataResult.Failure::class.java)
        val error = (result as DataResult.Failure).error as StatementException
        assertThat(error.messageEnum).isEqualTo(StatementExceptionMessage.OBJECT_NOT_FOUND)
    }

    @Test
    fun `should return OBJECT_NOT_FOUND for non-existent column`() {
        val result = dataAccess.rawQuery("SELECT non_existent_column FROM (SELECT 1 as id) dummy").execute()
        
        assertThat(result).isInstanceOf(DataResult.Failure::class.java)
        val error = (result as DataResult.Failure).error as StatementException
        assertThat(error.messageEnum).isEqualTo(StatementExceptionMessage.OBJECT_NOT_FOUND)
    }

    @Test
    fun `should return SYNTAX_ERROR for malformed SQL`() {
        val result = dataAccess.rawQuery("SELEC * FROM (SELECT 1) dummy").execute() // Typo in SELECT
        
        assertThat(result).isInstanceOf(DataResult.Failure::class.java)
        val error = (result as DataResult.Failure).error as StatementException
        assertThat(error.messageEnum).isEqualTo(StatementExceptionMessage.SYNTAX_ERROR)
    }

    @Test
    fun `should return DATA_EXCEPTION for division by zero`() {
        val result = dataAccess.rawQuery("SELECT 1/0").execute()
        
        assertThat(result).isInstanceOf(DataResult.Failure::class.java)
        val error = (result as DataResult.Failure).error as StatementException
        assertThat(error.messageEnum).isEqualTo(StatementExceptionMessage.DATA_EXCEPTION)
    }

    @Test
    fun `should return DATA_EXCEPTION for invalid integer format`() {
        val result = dataAccess.rawQuery("SELECT 'abc'::integer").execute()
        
        assertThat(result).isInstanceOf(DataResult.Failure::class.java)
        val error = (result as DataResult.Failure).error as StatementException
        assertThat(error.messageEnum).isEqualTo(StatementExceptionMessage.DATA_EXCEPTION)
    }

    @Test
    fun `should return DATA_EXCEPTION for value out of range`() {
        // big value into smallint
        val result = dataAccess.rawQuery("SELECT 99999::smallint").execute()
        
        assertThat(result).isInstanceOf(DataResult.Failure::class.java)
        val error = (result as DataResult.Failure).error as StatementException
        assertThat(error.messageEnum).isEqualTo(StatementExceptionMessage.DATA_EXCEPTION)
    }
}
