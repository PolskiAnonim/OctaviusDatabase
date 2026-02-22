package org.octavius.data.notification

/**
 * Represents a notification received from a PostgreSQL NOTIFY command.
 *
 * @param channel Name of the channel the notification was sent to.
 * @param payload Optional payload string sent with the notification. `null` if no payload was provided.
 * @param pid Process ID of the PostgreSQL backend that sent the notification.
 */
data class PgNotification(
    val channel: String,
    val payload: String?,
    val pid: Int
)