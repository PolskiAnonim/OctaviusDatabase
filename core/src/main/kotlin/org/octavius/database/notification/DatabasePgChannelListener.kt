package org.octavius.database.notification

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import org.octavius.data.DataResult
import org.octavius.data.exception.QueryContext
import org.octavius.data.notification.PgChannelListener
import org.octavius.data.notification.PgNotification
import org.octavius.database.exception.ExceptionTranslator
import org.postgresql.PGConnection
import java.sql.Connection

internal class DatabasePgChannelListener(
    private val connection: Connection
) : PgChannelListener {

    private val pgConnection: PGConnection = connection.unwrap(PGConnection::class.java)

    override fun listen(vararg channels: String): DataResult<Unit> {
        val sql = channels.joinToString("; ") { "LISTEN ${pgConnection.escapeIdentifier(it)}" }
        return try {
            channels.forEach { channel ->
                connection.createStatement().use { stmt ->
                    stmt.execute("LISTEN ${pgConnection.escapeIdentifier(channel)}")
                }
            }
            DataResult.Success(Unit)
        } catch (e: Exception) {
            val translated = ExceptionTranslator.translate(e, QueryContext(sql, emptyMap()))
            logger.error(translated) { "Error executing LISTEN on channels: ${channels.toList()}" }
            DataResult.Failure(translated)
        }
    }

    override fun unlisten(vararg channels: String): DataResult<Unit> {
        val sql = channels.joinToString("; ") { "UNLISTEN ${pgConnection.escapeIdentifier(it)}" }
        return try {
            channels.forEach { channel ->
                connection.createStatement().use { stmt ->
                    stmt.execute("UNLISTEN ${pgConnection.escapeIdentifier(channel)}")
                }
            }
            DataResult.Success(Unit)
        } catch (e: Exception) {
            val translated = ExceptionTranslator.translate(e, QueryContext(sql, emptyMap()))
            logger.error(translated) { "Error executing UNLISTEN on channels: ${channels.toList()}" }
            DataResult.Failure(translated)
        }
    }

    override fun unlistenAll(): DataResult<Unit> {
        val sql = "UNLISTEN *"
        return try {
            connection.createStatement().use { stmt ->
                stmt.execute(sql)
            }
            DataResult.Success(Unit)
        } catch (e: Exception) {
            val translated = ExceptionTranslator.translate(e, QueryContext(sql, emptyMap()))
            logger.error(translated) { "Error executing UNLISTEN *" }
            DataResult.Failure(translated)
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
