package org.octavius.data.notification

import kotlinx.coroutines.flow.Flow
import org.octavius.data.DataResult
import java.io.Closeable

/**
 * Manages PostgreSQL LISTEN/UNLISTEN subscriptions on a dedicated database connection.
 *
 * Each instance holds a single dedicated connection that lives for the duration
 * of the listener's lifecycle. Use [close] (or [kotlin.io.use]) to release the connection when done.
 *
 * Typical usage:
 * ```kotlin
 * db.createChannelListener().use { listener ->
 *     listener.listen("orders", "notifications")
 *     listener.notifications()
 *         .collect { notification ->
 *             println("Received on ${notification.channel}: ${notification.payload}")
 *         }
 * }
 * ```
 */
interface PgChannelListener : Closeable {

    /**
     * Subscribes to the given channels. Executes `LISTEN` for each channel.
     *
     * @param channels One or more channel names to subscribe to.
     * @return [DataResult.Success] if all channels were subscribed, [DataResult.Failure] on error.
     */
    fun listen(vararg channels: String): DataResult<Unit>

    /**
     * Unsubscribes from the given channels. Executes `UNLISTEN` for each channel.
     *
     * @param channels One or more channel names to unsubscribe from.
     * @return [DataResult.Success] if all channels were unsubscribed, [DataResult.Failure] on error.
     */
    fun unlisten(vararg channels: String): DataResult<Unit>

    /**
     * Unsubscribes from all currently subscribed channels. Executes `UNLISTEN *`.
     *
     * @return [DataResult.Success] if unsubscribed, [DataResult.Failure] on error.
     */
    fun unlistenAll(): DataResult<Unit>

    /**
     * Returns a cold [Flow] of [PgNotification] objects received on subscribed channels.
     *
     * The flow polls the underlying connection at a short interval (500 ms).
     * Cancel the collecting coroutine or call [close] to stop receiving notifications.
     *
     * Note: [listen] must be called before collecting this flow to receive any notifications.
     *
     * @return Flow emitting notifications as they arrive.
     */
    fun notifications(): Flow<DataResult<PgNotification>>
}