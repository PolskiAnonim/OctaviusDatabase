package io.github.octaviusframework.db.core.transaction

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.db.api.transaction.TransactionPropagation
import io.github.octaviusframework.db.core.config.DatabaseConfig
import io.github.octaviusframework.db.core.jdbc.DefaultJdbcTransactionProvider
import io.github.octaviusframework.db.core.jdbc.JdbcTemplate
import io.github.octaviusframework.db.core.type.PositionalQuery
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.*
import java.nio.file.Files
import java.nio.file.Paths

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefaultJdbcTransactionProviderTest {

    private lateinit var dataSource: HikariDataSource
    private lateinit var transactionProvider: DefaultJdbcTransactionProvider
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
        transactionProvider = DefaultJdbcTransactionProvider(dataSource)
        jdbcTemplate = JdbcTemplate(transactionProvider)

        jdbcTemplate.execute("DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public;")
        val initSql = String(Files.readAllBytes(Paths.get(this::class.java.classLoader.getResource("init-transaction-test-db.sql")!!.toURI())))
        jdbcTemplate.execute(initSql)
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @BeforeEach
    fun cleanup() {
        jdbcTemplate.update(PositionalQuery("TRUNCATE TABLE users, profiles, logs RESTART IDENTITY", listOf()))
    }

    @Test
    fun `REQUIRED should start a new transaction if none exists`() {
        transactionProvider.execute(TransactionPropagation.REQUIRED) {
            jdbcTemplate.update(PositionalQuery("INSERT INTO users (name) VALUES ('User 1')", listOf()))
        }

        val count = jdbcTemplate.query(PositionalQuery("SELECT COUNT(*) FROM users", listOf())) { rs, _ -> rs.getLong(1) }.first()
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `REQUIRED should join existing transaction`() {
        transactionProvider.execute(TransactionPropagation.REQUIRED) {
            jdbcTemplate.update(PositionalQuery("INSERT INTO users (name) VALUES ('User 1')", listOf()))

            transactionProvider.execute(TransactionPropagation.REQUIRED) {
                jdbcTemplate.update(PositionalQuery("INSERT INTO users (name) VALUES ('User 2')", listOf()))
            }
        }

        val count = jdbcTemplate.query(PositionalQuery("SELECT COUNT(*) FROM users", listOf())) { rs, _ -> rs.getLong(1) }.first()
        assertThat(count).isEqualTo(2)
    }

    @Test
    fun `REQUIRED should rollback everything if inner fails`() {
        assertThatThrownBy {
            transactionProvider.execute(TransactionPropagation.REQUIRED) {
                jdbcTemplate.update(PositionalQuery("INSERT INTO users (name) VALUES ('User 1')", listOf()))

                transactionProvider.execute(TransactionPropagation.REQUIRED) {
                    jdbcTemplate.update(PositionalQuery("INSERT INTO users (name) VALUES ('User 2')", listOf()))
                    throw RuntimeException("Force rollback")
                }
            }
        }.isInstanceOf(RuntimeException::class.java)

        val count = jdbcTemplate.query(PositionalQuery("SELECT COUNT(*) FROM users", listOf())) { rs, _ -> rs.getLong(1) }.first()
        assertThat(count).isEqualTo(0)
    }

    @Test
    fun `REQUIRES_NEW should start a new independent transaction`() {
        runCatching {
            transactionProvider.execute(TransactionPropagation.REQUIRED) {
                jdbcTemplate.update(PositionalQuery("INSERT INTO users (name) VALUES ('User 1')", listOf()))

                transactionProvider.execute(TransactionPropagation.REQUIRES_NEW) {
                    jdbcTemplate.update(PositionalQuery("INSERT INTO users (name) VALUES ('User 2')", listOf()))
                }

                throw RuntimeException("Rollback outer")
            }
        }

        // User 2 should be committed, User 1 should be rolled back
        val names = jdbcTemplate.query(PositionalQuery("SELECT name FROM users", listOf())) { rs, _ -> rs.getString(1) }
        assertThat(names).containsExactly("User 2")
    }

    @Test
    fun `NESTED should allow rolling back only nested part`() {
        transactionProvider.execute(TransactionPropagation.REQUIRED) {
            jdbcTemplate.update(PositionalQuery("INSERT INTO users (name) VALUES ('User 1')", listOf()))

            try {
                transactionProvider.execute(TransactionPropagation.NESTED) {
                    jdbcTemplate.update(PositionalQuery("INSERT INTO users (name) VALUES ('User 2')", listOf()))
                    throw RuntimeException("Rollback inner")
                }
            } catch (e: Exception) {
                // Ignore
            }

            jdbcTemplate.update(PositionalQuery("INSERT INTO users (name) VALUES ('User 3')", listOf()))
        }

        // User 1 and User 3 should be committed, User 2 should be rolled back
        val names = jdbcTemplate.query(PositionalQuery("SELECT name FROM users ORDER BY name", listOf())) { rs, _ -> rs.getString(1) }
        assertThat(names).containsExactly("User 1", "User 3")
    }

    @Test
    fun `setRollbackOnly should trigger rollback at the end of transaction`() {
        transactionProvider.execute(TransactionPropagation.REQUIRED) { status ->
            jdbcTemplate.update(PositionalQuery("INSERT INTO users (name) VALUES ('User 1')", listOf()))
            status.setRollbackOnly()
        }

        val count = jdbcTemplate.query(PositionalQuery("SELECT COUNT(*) FROM users", listOf())) { rs, _ -> rs.getLong(1) }.first()
        assertThat(count).isEqualTo(0)
    }
}