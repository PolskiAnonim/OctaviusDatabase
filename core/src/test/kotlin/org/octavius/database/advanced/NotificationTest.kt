package org.octavius.database.advanced

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.data.DataResult
import org.octavius.database.AbstractIntegrationTest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationTest: AbstractIntegrationTest() {

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

            val notification = withTimeout(5.seconds) {
                listener.notifications().first()
            }

            assertThat(notification.channel).isEqualTo("orders")
            assertThat(notification.payload).isEqualTo("order_99")
            assertThat(notification.pid).isGreaterThan(0)
        }
    }

    @Test
    fun `should receive notification without payload`() = runBlocking {
        dataAccess.createChannelListener().use { listener ->
            listener.listen("pings")
            dataAccess.notify("pings")

            val notification = withTimeout(5_000.milliseconds) {
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

            val notifications = withTimeout(5_000.milliseconds) {
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

            val first = withTimeout(5.seconds) { listener.notifications().first() }
            assertThat(first.payload).isEqualTo("before")

            listener.unlisten("events")
            dataAccess.notify("events", "after_unlisten")

            val stray = try {
                withTimeout(1_500.milliseconds) { listener.notifications().first() }
            } catch (_: TimeoutCancellationException) {
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

            val notification = withTimeout(5.seconds) { listener.notifications().first() }
            assertThat(notification.channel).isEqualTo("tx_channel")
            assertThat(notification.payload).isEqualTo("from_tx")
        }
    }
}