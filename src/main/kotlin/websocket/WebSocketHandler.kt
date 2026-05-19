package websocket

import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import model.enums.CardType
import model.ws.ClientEventType
import model.ws.ClientMessage
import model.ws.EventType
import model.ws.PlayerJoinedPayload
import model.ws.PlayerDisconnectedPayload
import model.ws.WsMessage
import repository.PlayerRepository
import repository.RoomRepository
import service.GameService
import service.GameStateManager
import service.RoomService
import java.util.UUID

fun Route.webSocketRoutes(
    connectionManager: ConnectionManager,
    gameService: GameService,
    roomRepository: RoomRepository,
    playerRepository: PlayerRepository
) {
    webSocket("/ws/game") {
        val roomIdStr = call.request.queryParameters["roomId"]
        val playerIdStr = call.request.queryParameters["playerId"]

        if (roomIdStr == null || playerIdStr == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "roomId and playerId are required"))
            return@webSocket
        }

        val roomId = runCatching { UUID.fromString(roomIdStr) }.getOrElse {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid roomId"))
            return@webSocket
        }
        val playerId = runCatching { UUID.fromString(playerIdStr) }.getOrElse {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid playerId"))
            return@webSocket
        }

        val player = playerRepository.findById(playerId)
        val room = roomRepository.findById(roomId)
        if (player == null || room == null || player.roomId != roomId) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
            return@webSocket
        }

        val isReconnect = connectionManager.isConnected(roomId, playerId)
        connectionManager.addSession(roomId, playerId, this)

        if (isReconnect && GameStateManager.exists(roomId)) {
            launch { gameService.handlePlayerReconnect(roomId, playerId) }
        } else {
            // 他プレイヤーへ参加通知
            val msg = WsMessage(
                EventType.PLAYER_JOINED,
                Json.encodeToJsonElement(PlayerJoinedPayload(playerId.toString(), player.userName, player.seatOrder))
            )
            connectionManager.broadcastExcept(roomId, playerId, msg)
        }

        try {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    val clientMsg = runCatching { Json.decodeFromString<ClientMessage>(text) }.getOrNull() ?: continue
                    try {
                        handleClientMessage(roomId, playerId, clientMsg, connectionManager, gameService, roomRepository)
                    } catch (e: Exception) {
                        println("Error handling client message: ${e.message}")
                        e.printStackTrace()
                        val errorMsg = WsMessage(EventType.ERROR, Json.encodeToJsonElement(
                            mapOf("code" to "INTERNAL_ERROR", "message" to (e.message ?: "内部エラーが発生しました"))
                        ))
                        connectionManager.sendTo(roomId, playerId, errorMsg)
                    }
                }
            }
        } finally {
            connectionManager.removeSession(roomId, playerId)

            if (GameStateManager.exists(roomId)) {
                launch { gameService.handlePlayerDisconnect(roomId, playerId) }
            }

            val disconnectMsg = WsMessage(
                EventType.PLAYER_DISCONNECTED,
                Json.encodeToJsonElement(PlayerDisconnectedPayload(playerId.toString(), player.userName))
            )
            connectionManager.broadcast(roomId, disconnectMsg)
        }
    }
}

private suspend fun handleClientMessage(
    roomId: UUID,
    playerId: UUID,
    msg: ClientMessage,
    connectionManager: ConnectionManager,
    gameService: GameService,
    roomRepository: RoomRepository
) {
    val payload = msg.payload

    when (msg.type) {
        ClientEventType.GAME_START -> {
            val room = roomRepository.findById(roomId) ?: return
            if (room.hostPlayerId != playerId) return
            gameService.initGame(roomId, playerId)
        }

        ClientEventType.REMATCH_REQUEST -> {
            gameService.handleRematch(roomId, playerId)
        }

        ClientEventType.ACTION_DRAW_CARD -> {
            gameService.handleDrawCard(roomId, playerId)
        }

        ClientEventType.ACTION_USE_CARD -> {
            val cardId = payload["cardId"]?.jsonPrimitive?.content?.let { UUID.fromString(it) } ?: return
            val cardTypeStr = payload["cardType"]?.jsonPrimitive?.content ?: return
            val cardType = runCatching { CardType.valueOf(cardTypeStr) }.getOrElse { return }
            val params = payload["params"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            gameService.handleUseCard(roomId, playerId, cardId, cardType, params)
        }

        ClientEventType.ACTION_DISCARD_CARD -> {
            val cardId = payload["cardId"]?.jsonPrimitive?.content?.let { UUID.fromString(it) } ?: return
            gameService.handleDiscardCard(roomId, playerId, cardId)
        }

        ClientEventType.ACTION_EXCHANGE_HAND -> {
            val targetId = payload["targetPlayerId"]?.jsonPrimitive?.content?.let { UUID.fromString(it) } ?: return
            gameService.handleExchangeHand(roomId, playerId, targetId)
        }

        ClientEventType.ACTION_EXCHANGE_APPLE -> {
            val targetId = payload["targetPlayerId"]?.jsonPrimitive?.content?.let { UUID.fromString(it) } ?: return
            gameService.handleExchangeApple(roomId, playerId, targetId)
        }

        ClientEventType.ACTION_CHECK_OWN_APPLE -> {
            gameService.handleCheckOwnApple(roomId, playerId)
        }

        ClientEventType.ACTION_USE_ABILITY -> {
            val params = payload["params"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            gameService.handleUseAbility(roomId, playerId, params)
        }

        ClientEventType.RESPONSE_PREFERENCE -> {
            val answer = payload["answer"]?.jsonPrimitive?.boolean ?: return
            gameService.handlePreferenceResponse(roomId, playerId, answer)
        }

        ClientEventType.RESPONSE_QUEEN_EXCHANGE -> {
            val targetId = payload["targetPlayerId"]?.jsonPrimitive?.content?.let { UUID.fromString(it) } ?: return
            gameService.handleQueenExchangeResponse(roomId, playerId, targetId)
        }

        ClientEventType.ACTION_THINK_TIME -> {
            gameService.handleThinkTime(roomId, playerId)
        }
    }
}
