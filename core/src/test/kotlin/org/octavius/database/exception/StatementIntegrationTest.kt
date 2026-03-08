package org.octavius.database.exception

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.octavius.data.DataResult
import org.octavius.data.exception.*
import org.octavius.database.OctaviusDatabase
import org.octavius.database.config.DatabaseConfig

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatementIntegrationTest {

    private lateinit var dataAccess: org.octavius.data.DataAccess

    @BeforeAll
    fun setup() {
        val config = DatabaseConfig.loadFromFile("test-database.properties").copy(
            disableFlyway = true,
            disableCoreTypeInitialization = true
        )
        dataAccess = OctaviusDatabase.fromConfig(config)
    }

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
