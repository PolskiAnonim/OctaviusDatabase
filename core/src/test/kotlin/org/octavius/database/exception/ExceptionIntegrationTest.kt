package org.octavius.database.exception

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.octavius.data.DataResult
import org.octavius.data.exception.ConcurrencyErrorType
import org.octavius.data.exception.ConcurrencyException
import org.octavius.data.exception.ConnectionException
import org.octavius.database.OctaviusDatabase
import org.octavius.database.config.DatabaseConfig

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExceptionIntegrationTest {

    private lateinit var config: DatabaseConfig

    @BeforeAll
    fun setup() {
        config = DatabaseConfig.loadFromFile("test-database.properties").copy(disableFlyway = true, disableCoreTypeInitialization = true)
    }

    @Test
    fun `should throw ConnectionException when port is wrong`() {
        // GIVEN: config with wrong port
        val wrongConfig = config.copy(dbUrl = "jdbc:postgresql://localhost:5433/non_existent_db")
        
        // WHEN & THEN
        assertThrows<ConnectionException> {
            OctaviusDatabase.fromConfig(wrongConfig)
        }
    }

    @Test
    fun `should return ConcurrencyException on timeout`() {
        // GIVEN
        val dataAccess = OctaviusDatabase.fromConfig(config)
        
        // WHEN: Executing query that takes 2s with 1s timeout
        // PostgreSQL: SET statement_timeout = 1000;
        dataAccess.rawQuery("SET statement_timeout = 1000").execute()
        
        val result = dataAccess.rawQuery("SELECT pg_sleep(2)").execute()
        
        // THEN
        assertThat(result).isInstanceOf(DataResult.Failure::class.java)
        val failure = result as DataResult.Failure
        assertThat(failure.error).isInstanceOf(ConcurrencyException::class.java)
        val concError = failure.error as ConcurrencyException
        assertThat(concError.errorType).isEqualTo(ConcurrencyErrorType.TIMEOUT)
        
        // Cleanup
        dataAccess.rawQuery("SET statement_timeout = 0").execute()
    }

    @Test
    @Timeout(10)
    fun `should return ConcurrencyException on deadlock`() = runBlocking {
        // GIVEN
        val dataAccess = OctaviusDatabase.fromConfig(config)
        dataAccess.rawQuery("CREATE TABLE IF NOT EXISTS deadlock_test (id INT PRIMARY KEY, val TEXT)").execute()
        dataAccess.rawQuery("TRUNCATE deadlock_test").execute()
        dataAccess.rawQuery("INSERT INTO deadlock_test (id, val) VALUES (1, 'A'), (2, 'B')").execute()

        // TWO transactions locking rows in reverse order
        val deferred1 = async(Dispatchers.IO) {
            dataAccess.transaction { tx ->
                tx.rawQuery("UPDATE deadlock_test SET val = 'T1' WHERE id = 1").execute()
                Thread.sleep(1000) // Give T2 time to lock row 2
                tx.rawQuery("UPDATE deadlock_test SET val = 'T1' WHERE id = 2").execute()
            }
        }

        val deferred2 = async(Dispatchers.IO) {
            dataAccess.transaction { tx ->
                tx.rawQuery("UPDATE deadlock_test SET val = 'T2' WHERE id = 2").execute()
                Thread.sleep(1000) // Give T1 time to lock row 1
                tx.rawQuery("UPDATE deadlock_test SET val = 'T2' WHERE id = 1").execute()
            }
        }

        val res1 = deferred1.await()
        val res2 = deferred2.await()

        // One of them must fail with deadlock
        val anyDeadlock = (res1 is DataResult.Failure && (res1.error as? ConcurrencyException)?.errorType == ConcurrencyErrorType.DEADLOCK) ||
                         (res2 is DataResult.Failure && (res2.error as? ConcurrencyException)?.errorType == ConcurrencyErrorType.DEADLOCK)

        assertThat(anyDeadlock).isTrue()
        Unit
    }
}
