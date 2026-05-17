package websocket

import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.ws.WsMessage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ConnectionManager {

    private val mutex = Mutex()
    // roomId -> (playerId -> WebSocketSession)
    private val sessions = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, DefaultWebSocketSession>>()

    suspend fun addSession(roomId: UUID, playerId: UUID, session: DefaultWebSocketSession) {
        mutex.withLock {
            sessions.getOrPut(roomId) { ConcurrentHashMap() }[playerId] = session
        }
    }

    suspend fun removeSession(roomId: UUID, playerId: UUID) {
        mutex.withLock {
            sessions[roomId]?.remove(playerId)
            if (sessions[roomId]?.isEmpty() == true) {
                sessions.remove(roomId)
            }
        }
    }

    fun getSession(roomId: UUID, playerId: UUID): DefaultWebSocketSession? =
        sessions[roomId]?.get(playerId)

    fun getSessionsInRoom(roomId: UUID): Map<UUID, DefaultWebSocketSession> =
        sessions[roomId]?.toMap() ?: emptyMap()

    suspend fun sendTo(roomId: UUID, playerId: UUID, message: WsMessage) {
        getSession(roomId, playerId)?.send(Frame.Text(Json.encodeToString(message)))
    }

    suspend fun broadcast(roomId: UUID, message: WsMessage) {
        val text = Frame.Text(Json.encodeToString(message))
        getSessionsInRoom(roomId).values.forEach { session ->
            runCatching { session.send(text) }
        }
    }

    suspend fun broadcastExcept(roomId: UUID, excludePlayerId: UUID, message: WsMessage) {
        val text = Frame.Text(Json.encodeToString(message))
        getSessionsInRoom(roomId).forEach { (playerId, session) ->
            if (playerId != excludePlayerId) {
                runCatching { session.send(text) }
            }
        }
    }

    fun isConnected(roomId: UUID, playerId: UUID): Boolean =
        sessions[roomId]?.containsKey(playerId) == true

    fun connectedPlayers(roomId: UUID): Set<UUID> =
        sessions[roomId]?.keys?.toSet() ?: emptySet()
}
