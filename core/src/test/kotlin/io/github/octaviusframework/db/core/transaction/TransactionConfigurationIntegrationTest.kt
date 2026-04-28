package io.github.octaviusframework.db.core.transaction

import io.github.octaviusframework.db.api.DataResult
import io.github.octaviusframework.db.api.builder.execute
import io.github.octaviusframework.db.api.builder.toField
import io.github.octaviusframework.db.api.builder.toFieldStrict
import io.github.octaviusframework.db.api.exception.ConcurrencyErrorType
import io.github.octaviusframework.db.api.exception.ConcurrencyException
import io.github.octaviusframework.db.api.exception.StatementException
import io.github.octaviusframework.db.api.exception.StatementExceptionMessage
import io.github.octaviusframework.db.api.getOrThrow
import io.github.octaviusframework.db.api.transaction.IsolationLevel
import io.github.octaviusframework.db.core.AbstractIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class TransactionConfigurationIntegrationTest: AbstractIntegrationTest() {

    override val scriptName: String = "init-transaction-test-db.sql"

    @BeforeEach
    fun cleanup() {
        dataAccess.rawQuery("TRUNCATE TABLE users CASCADE").execute()
    }

    @Test
    fun `should enforce transaction timeout using SET LOCAL statement_timeout`() {
        assertThatThrownBy {
            dataAccess.transaction(timeoutSeconds = 1) { tx ->
                tx.rawQuery("SELECT pg_sleep(2)").execute()
            }.getOrThrow()
        }.isInstanceOf(ConcurrencyException::class.java)
            .matches { (it as ConcurrencyException).errorType == ConcurrencyErrorType.TIMEOUT }
    }

    @Test
    fun `should enforce read-only mode`() {
        val result = dataAccess.transaction(readOnly = true) { tx ->
            tx.insertInto("users")
                .values(listOf("name"))
                .execute("name" to "Read Only User")
        }

        assertThat(result).isInstanceOf(DataResult.Failure::class.java)
        val failure = result as DataResult.Failure
        assertThat(failure.error).isInstanceOf(StatementException::class.java)
        assertThat((failure.error as StatementException).messageEnum).isEqualTo(StatementExceptionMessage.INVALID_TRANSACTION_STATE)
    }

    @Test
    fun `should correctly apply serializable isolation level to the session`() {
        dataAccess.transaction(isolation = IsolationLevel.SERIALIZABLE) { tx ->
            val level = tx.rawQuery("SHOW transaction_isolation").toFieldStrict<String>().getOrThrow()
            assertThat(level).isEqualTo("serializable")
            DataResult.Success(Unit)
        }.getOrThrow()
    }

    @Test
    fun `should catch serialization failure in SERIALIZABLE mode`() {
        dataAccess.rawQuery("INSERT INTO users (name) VALUES ('User1'), ('User2')").execute().getOrThrow()

        val barrier = CyclicBarrier(2)
        val t1Error = AtomicReference<Throwable?>(null)
        val t2Error = AtomicReference<Throwable?>(null)

        val t1 = thread {
            try {
                dataAccess.transaction(isolation = IsolationLevel.SERIALIZABLE) { tx ->
                    // Synchronize start to ensure both have connections and are in SERIALIZABLE mode
                    barrier.await(60, TimeUnit.SECONDS)
                    val u1 = tx.rawQuery("SELECT name FROM users WHERE name = 'User1'").toField<String>().getOrThrow()
                    
                    // Synchronize after read to ensure both have taken snapshots and SIREAD locks before any update
                    barrier.await(60, TimeUnit.SECONDS)
                    
                    tx.update("users").setValue("name").where("name = 'User2'").execute("name" to "Modified by T1 ($u1)")
                }.getOrThrow()
            } catch (e: Throwable) {
                t1Error.set(e)
            }
        }

        val t2 = thread {
            try {
                dataAccess.transaction(isolation = IsolationLevel.SERIALIZABLE) { tx ->
                    // Synchronize start
                    barrier.await(60, TimeUnit.SECONDS)
                    val u2 = tx.rawQuery("SELECT name FROM users WHERE name = 'User2'").toField<String>().getOrThrow()
                    
                    // Synchronize after read
                    barrier.await(60, TimeUnit.SECONDS)
                    
                    tx.update("users").setValue("name").where("name = 'User1'").execute("name" to "Modified by T2 ($u2)")
                }.getOrThrow()
            } catch (e: Throwable) {
                t2Error.set(e)
            }
        }

        t1.join(120000)
        t2.join(120000)

        val error1 = t1Error.get()
        val error2 = t2Error.get()
        
        val anySerializationError = (error1 is ConcurrencyException && error1.errorType == ConcurrencyErrorType.SERIALIZATION_FAILURE) ||
                                    (error2 is ConcurrencyException && error2.errorType == ConcurrencyErrorType.SERIALIZATION_FAILURE)

        assertThat(anySerializationError)
            .withFailMessage("Expected at least one thread to fail with SERIALIZATION_FAILURE, but errors were: T1=$error1, T2=$error2")
            .isTrue()
    }
}
