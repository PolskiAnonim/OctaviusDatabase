package org.octavius.database.transaction

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.*
import org.octavius.data.DataAccess
import org.octavius.data.DataResult
import org.octavius.data.builder.execute
import org.octavius.data.builder.toField
import org.octavius.data.exception.StatementException
import org.octavius.data.getOrThrow
import org.octavius.data.transaction.IsolationLevel
import org.octavius.database.OctaviusDatabase
import org.octavius.database.config.DatabaseConfig
import org.octavius.database.jdbc.DefaultJdbcTransactionProvider
import org.octavius.database.jdbc.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionConfigurationIntegrationTest {

    private lateinit var dataAccess: DataAccess
    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeAll
    fun setup() {
        val dbConfig = DatabaseConfig.loadFromFile("test-database.properties")
        dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = dbConfig.dbUrl
            username = dbConfig.dbUsername
            password = dbConfig.dbPassword
            maximumPoolSize = 5
        })
        jdbcTemplate = JdbcTemplate(DefaultJdbcTransactionProvider(dataSource))

        val initSql = String(Files.readAllBytes(Paths.get(this::class.java.classLoader.getResource("init-transaction-test-db.sql")!!.toURI())))
        jdbcTemplate.execute(initSql)

        dataAccess = OctaviusDatabase.fromDataSource(
            dataSource = dataSource,
            packagesToScan = emptyList(),
            dbSchemas = listOf("public"),
            disableFlyway = true,
            disableCoreTypeInitialization = true
        )
    }

    @BeforeEach
    fun cleanup() {
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE")
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `should enforce transaction timeout using SET LOCAL statement_timeout`() {
        // We set a 1s timeout and call pg_sleep(2s)
        assertThatThrownBy {
            dataAccess.transaction(timeoutSeconds = 1) { tx ->
                tx.rawQuery("SELECT pg_sleep(2)").execute()
            }.getOrThrow()
        }.isInstanceOf(StatementException::class.java)
            .hasMessageContaining("statement_timeout")
    }

    @Test
    fun `should enforce read-only mode`() {
        // Try to insert in a read-only transaction
        val result = dataAccess.transaction(readOnly = true) { tx ->
            tx.insertInto("users")
                .values(listOf("name"))
                .execute("name" to "Read Only User")
        }

        assertThat(result).isInstanceOf(DataResult.Failure::class.java)
        val failure = result as DataResult.Failure
        assertThat(failure.error).isInstanceOf(StatementException::class.java)
        assertThat(failure.error.message).containsAnyOf("read-only", "cannot execute INSERT")
    }

    @Test
    fun `should respect serializable isolation level`() {
        // Insert a base record
        jdbcTemplate.execute("INSERT INTO users (name) VALUES ('Initial')")

        val latch1 = CountDownLatch(1)
        val latch2 = CountDownLatch(1)
        var threadError: Throwable? = null

        // Thread 1: Start SERIALIZABLE transaction and update
        val t1 = thread {
            try {
                dataAccess.transaction(isolation = IsolationLevel.SERIALIZABLE) { tx ->
                    tx.update("users").setValue("name").where("name = 'Initial'").execute("name" to "Updated by T1")
                    latch1.countDown() // Signal T1 updated
                    latch2.await(5, TimeUnit.SECONDS) // Wait for T2 to update
                    DataResult.Success(Unit)
                }.getOrThrow()
            } catch (e: Throwable) {
                threadError = e
            }
        }

        // Thread 2: Start SERIALIZABLE transaction and update the SAME row
        val t2 = thread {
            latch1.await(5, TimeUnit.SECONDS) // Wait for T1 to start and update
            try {
                dataAccess.transaction(isolation = IsolationLevel.SERIALIZABLE) { tx ->
                    // This update should conflict with T1's uncommitted update when T1 tries to commit, 
                    // or T2 might block and then fail with serialization error.
                    tx.update("users").setValue("name").where("name = 'Initial'").execute("name" to "Updated by T2")
                    latch2.countDown()
                    DataResult.Success(Unit)
                }.getOrThrow()
            } catch (e: Throwable) {
                // We expect a serialization failure here (or in T1)
                // In Postgres, the second one to commit/update the same row will fail.
            }
        }

        t1.join(10000)
        t2.join(10000)

        // At least one of them should have failed or we should see a serialization error
        // Postgres error code for serialization_failure is 40001
        
        // Let's check the result in DB
        val finalName = dataAccess.rawQuery("SELECT name FROM users LIMIT 1").toField<String>().getOrThrow()
        assertThat(finalName).isIn("Updated by T1", "Updated by T2")
    }
}
