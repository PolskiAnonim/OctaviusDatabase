package org.octavius.database.exception

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.octavius.data.DataResult
import org.octavius.data.exception.*
import org.octavius.database.OctaviusDatabase
import org.octavius.database.config.DatabaseConfig

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConstraintIntegrationTest {

    private lateinit var dataAccess: org.octavius.data.DataAccess

    @BeforeAll
    fun setup() {
        val config = DatabaseConfig.loadFromFile("test-database.properties").copy(
            disableFlyway = true,
        )
        dataAccess = OctaviusDatabase.fromConfig(config)

        // Setup tables
        dataAccess.rawQuery("""
            DROP TABLE IF EXISTS constraint_child;
            DROP TABLE IF EXISTS constraint_test;
            
            CREATE TABLE constraint_test (
                id INT PRIMARY KEY,
                name TEXT NOT NULL,
                age INT CHECK (age > 0),
                email TEXT UNIQUE
            );
            
            CREATE TABLE constraint_child (
                id INT PRIMARY KEY,
                parent_id INT REFERENCES constraint_test(id)
            );
        """.trimIndent()).execute()
    }

    @Test
    fun `should return UNIQUE_CONSTRAINT_VIOLATION`() {
        dataAccess.rawQuery("INSERT INTO constraint_test(id, name, email) VALUES (1, 'Test', 'test@test.com')").execute()
        
        val result = dataAccess.rawQuery("INSERT INTO constraint_test(id, name, email) VALUES (2, 'Test2', 'test@test.com')").execute()
        
        assertThat(result).isInstanceOf(DataResult.Failure::class.java)
        val error = (result as DataResult.Failure).error as ConstraintViolationException
        assertThat(error.messageEnum).isEqualTo(ConstraintViolationExceptionMessage.UNIQUE_CONSTRAINT_VIOLATION)
        assertThat(error.constraintName).contains("email")
    }

    @Test
    fun `should return NOT_NULL_VIOLATION`() {
        val result = dataAccess.rawQuery("INSERT INTO constraint_test(id, name) VALUES (3, NULL)").execute()
        
        assertThat(result).isInstanceOf(DataResult.Failure::class.java)
        val error = (result as DataResult.Failure).error as ConstraintViolationException
        assertThat(error.messageEnum).isEqualTo(ConstraintViolationExceptionMessage.NOT_NULL_VIOLATION)
    }

    @Test
    fun `should return CHECK_CONSTRAINT_VIOLATION`() {
        val result = dataAccess.rawQuery("INSERT INTO constraint_test(id, name, age) VALUES (4, 'Test', -1)").execute()
        
        assertThat(result).isInstanceOf(DataResult.Failure::class.java)
        val error = (result as DataResult.Failure).error as ConstraintViolationException
        assertThat(error.messageEnum).isEqualTo(ConstraintViolationExceptionMessage.CHECK_CONSTRAINT_VIOLATION)
    }

    @Test
    fun `should return FOREIGN_KEY_VIOLATION`() {
        val result = dataAccess.rawQuery("INSERT INTO constraint_child(id, parent_id) VALUES (1, 999)").execute()
        
        assertThat(result).isInstanceOf(DataResult.Failure::class.java)
        val error = (result as DataResult.Failure).error as ConstraintViolationException
        assertThat(error.messageEnum).isEqualTo(ConstraintViolationExceptionMessage.FOREIGN_KEY_VIOLATION)
    }

    @Test
    fun `should return StatementException for bad SQL`() {
        val result = dataAccess.rawQuery("SELECT * FROM non_existent_table_xyz").execute()

        assertThat(result).isInstanceOf(DataResult.Failure::class.java)
        val error = (result as DataResult.Failure).error
        assertThat(error).isInstanceOf(StatementException::class.java)
        val stmtError = error as StatementException
        assertThat(stmtError.messageEnum).isEqualTo(StatementExceptionMessage.OBJECT_NOT_FOUND)
        assertThat(stmtError.detail).contains("non_existent_table_xyz")
    }
}
