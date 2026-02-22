package org.octavius.database.notification

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import org.octavius.data.notification.PgChannelListener
import org.octavius.data.notification.PgNotification
import org.postgresql.PGConnection
import java.sql.Connection

internal class DatabasePgChannelListener(
    private val connection: Connection
) : PgChannelListener {

    private val pgConnection = connection.unwrap(PGConnection::class.java)

    override fun listen(vararg channels: String) {
        channels.forEach { channel ->
            connection.createStatement().use { stmt ->
                stmt.execute("LISTEN \"$channel\"")
            }
        }
    }

    override fun unlisten(vararg channels: String) {
        channels.forEach { channel ->
            connection.createStatement().use { stmt ->
                stmt.execute("UNLISTEN \"$channel\"")
            }
        }
    }

    override fun unlistenAll() {
        connection.createStatement().use { stmt ->
            stmt.execute("UNLISTEN *")
        }
    }

    override fun notifications(): Flow<PgNotification> = flow {
        while (currentCoroutineContext().isActive) {
            val notifs = pgConnection.getNotifications(POLL_TIMEOUT_MS)
            notifs?.forEach { notif ->
                emit(PgNotification(notif.name, notif.parameter, notif.pid))
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun close() {
        if (connection.isClosed) return
        try {
            unlistenAll()
        } catch (e: Exception) {
            logger.warn(e) { "Error executing UNLISTEN * during listener close" }
        }
        try {
            connection.close()
        } catch (e: Exception) {
            logger.warn(e) { "Error closing listener connection" }
        }
    }

    companion object {
        private const val POLL_TIMEOUT_MS = 500
        private val logger = KotlinLogging.logger {}
    }
}