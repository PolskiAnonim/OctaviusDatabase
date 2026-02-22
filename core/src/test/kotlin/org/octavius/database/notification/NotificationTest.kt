package org.octavius.database.notification

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.data.DataAccess
import org.octavius.data.DataResult
import org.octavius.database.OctaviusDatabase
import org.octavius.database.config.DatabaseConfig

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationTest {

    private lateinit var dataAccess: DataAccess

    @BeforeAll
    fun setup() {
        val dbConfig = DatabaseConfig.loadFromFile("test-database.properties")
        val dbUrl = dbConfig.dbUrl
        if (!dbUrl.contains("localhost:5432") || !dbUrl.endsWith("octavius_test")) {
            throw IllegalStateException("ABORTING TEST! Attempting to run on a non-test database: $dbUrl")
        }

        val dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = dbConfig.dbUrl
            username = dbConfig.dbUsername
            password = dbConfig.dbPassword
        })
        dataAccess = OctaviusDatabase.fromDataSource(
            dataSource = dataSource,
            packagesToScan = listOf(),
            dbSchemas = dbConfig.dbSchemas,
            disableFlyway = true,
            disableCoreTypeInitialization = true
        )
    }

    @Test
    fun `notify should return DataResult Success`() {
        val result = dataAccess.notify("test_notify_basic", "ping")
        assertThat(result).isInstanceOf(DataResult.Success::class.java)
    }

    @Test
    fun `should receive notification with payload on subscribed channel`() = runBlocking<Unit> {
        dataAccess.createChannelListener().use { listener ->
            listener.listen("orders")
            dataAccess.notify("orders", "order_99")

            val notification = withTimeout(5_000) {
                listener.notifications().first()
            }

            assertThat(notification.channel).isEqualTo("orders")
            assertThat(notification.payload).isEqualTo("order_99")
            assertThat(notification.pid).isGreaterThan(0)
        }
    }

    @Test
    fun `should receive notification without payload`() = runBlocking<Unit> {
        dataAccess.createChannelListener().use { listener ->
            listener.listen("pings")
            dataAccess.notify("pings")

            val notification = withTimeout(5_000) {
                listener.notifications().first()
            }

            assertThat(notification.channel).isEqualTo("pings")
            assertThat(notification.payload).isNullOrEmpty()
        }
    }

    @Test
    fun `should receive notifications from multiple subscribed channels`() = runBlocking<Unit> {
        dataAccess.createChannelListener().use { listener ->
            listener.listen("channel_a", "channel_b")
            dataAccess.notify("channel_a", "msg_a")
            dataAccess.notify("channel_b", "msg_b")

            val notifications = withTimeout(5_000) {
                listener.notifications().take(2).toList()
            }

            assertThat(notifications.map { it.channel }).containsExactlyInAnyOrder("channel_a", "channel_b")
            assertThat(notifications.map { it.payload }).containsExactlyInAnyOrder("msg_a", "msg_b")
        }
    }

    @Test
    fun `should not receive notifications on channel after unlisten`() = runBlocking<Unit> {
        dataAccess.createChannelListener().use { listener ->
            listener.listen("events")
            dataAccess.notify("events", "before")

            val first = withTimeout(5_000) { listener.notifications().first() }
            assertThat(first.payload).isEqualTo("before")

            listener.unlisten("events")
            dataAccess.notify("events", "after_unlisten")

            val stray = try {
                withTimeout(1_500) { listener.notifications().first() }
            } catch (e: TimeoutCancellationException) {
                null
            }
            assertThat(stray).isNull()
        }
    }

    @Test
    fun `notify inside transaction should deliver notification only on commit`() = runBlocking<Unit> {
        dataAccess.createChannelListener().use { listener ->
            listener.listen("tx_channel")

            dataAccess.transaction {
                dataAccess.notify("tx_channel", "from_tx")
                DataResult.Success(Unit)
            }

            val notification = withTimeout(5_000) { listener.notifications().first() }
            assertThat(notification.channel).isEqualTo("tx_channel")
            assertThat(notification.payload).isEqualTo("from_tx")
        }
    }
}