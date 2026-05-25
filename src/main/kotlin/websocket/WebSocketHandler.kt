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

        // 再接続判定：
        // 旧コードは connectionManager.isConnected() で判定していたが、
        // 直前の切断時に finally ブロックで removeSession 済みのため常に false となり、
        // 実際の再接続が「新規参加」扱いになる不具合があった。
        // ゲーム状態が存在し、かつ自分がそのゲームの参加プレイヤーであれば再接続とみなす。
        val isReconnect = GameStateManager.exists(roomId) &&
            GameStateManager.get(roomId)?.players?.containsKey(playerId) == true
        connectionManager.addSession(roomId, playerId, this)

        if (isReconnect) {
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
                        handleClientMessage(roomId, playerId, clientMsg, connectionManager, gameService, roomRepository, playerRepository)
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
    roomRepository: RoomRepository,
    playerRepository: PlayerRepository
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

        ClientEventType.DISBAND_ROOM -> {
            val room = roomRepository.findById(roomId) ?: return
            if (room.hostPlayerId != playerId) return
            val msg = WsMessage(EventType.ROOM_DISBANDED, kotlinx.serialization.json.JsonObject(emptyMap()))
            connectionManager.broadcastExcept(roomId, playerId, msg)
        }

        ClientEventType.LEAVE_ROOM -> {
            val player = playerRepository.findById(playerId) ?: return
            playerRepository.delete(playerId)
            // Broadcast to remaining players
            val leftMsg = WsMessage(EventType.PLAYER_LEFT, Json.encodeToJsonElement(
                mapOf("playerId" to playerId.toString(), "userName" to player.userName)
            ))
            connectionManager.broadcastExcept(roomId, playerId, leftMsg)
        }

        ClientEventType.SWAP_SEATS -> {
            val room = roomRepository.findById(roomId) ?: return
            if (room.hostPlayerId != playerId) return
            val playerIdA = payload["playerIdA"]?.jsonPrimitive?.content ?: return
            val playerIdB = payload["playerIdB"]?.jsonPrimitive?.content ?: return
            val pidA = UUID.fromString(playerIdA)
            val pidB = UUID.fromString(playerIdB)
            playerRepository.swapSeatOrders(pidA, pidB)
            // Broadcast the swap to all
            val swapMsg = WsMessage(EventType.SEATS_SWAPPED, Json.encodeToJsonElement(
                mapOf("playerIdA" to playerIdA, "playerIdB" to playerIdB)
            ))
            connectionManager.broadcast(roomId, swapMsg)
        }

        ClientEventType.SHUFFLE_SEATS -> {
            val room = roomRepository.findById(roomId) ?: return
            if (room.hostPlayerId != playerId) return
            val players = playerRepository.findByRoomId(roomId)
            val shuffledOrders = players.map { it.seatOrder }.shuffled()
            // First set all to negative temp values to avoid unique constraint
            players.forEachIndexed { index, player ->
                playerRepository.updateSeatOrder(player.id, -(index + 1))
            }
            // Then set to final shuffled values
            players.forEachIndexed { index, player ->
                playerRepository.updateSeatOrder(player.id, shuffledOrders[index])
            }
            val shuffleMsg = WsMessage(EventType.SEATS_SWAPPED, Json.encodeToJsonElement(
                mapOf("shuffled" to "true")
            ))
            connectionManager.broadcast(roomId, shuffleMsg)
        }

        ClientEventType.DRAG_SEAT -> {
            val room = roomRepository.findById(roomId) ?: return
            if (room.hostPlayerId != playerId) return
            val dragPlayerId = payload["dragPlayerId"]?.jsonPrimitive?.content ?: return
            val overSeatIndex = payload["overSeatIndex"]?.jsonPrimitive?.intOrNull
            val dragMsg = WsMessage(EventType.DRAG_SEAT_UPDATE, Json.encodeToJsonElement(
                mapOf("dragPlayerId" to dragPlayerId, "overSeatIndex" to (overSeatIndex?.toString() ?: ""))
            ))
            connectionManager.broadcastExcept(roomId, playerId, dragMsg)
        }

        ClientEventType.PROCEED_TO_REVEAL -> {
            val room = roomRepository.findById(roomId) ?: return
            if (room.hostPlayerId != playerId) return
            gameService.handleProceedToReveal(roomId, playerId)
        }

        ClientEventType.PROCEED_TO_RESULT -> {
            val room = roomRepository.findById(roomId) ?: return
            if (room.hostPlayerId != playerId) return
            gameService.handleProceedToResult(roomId, playerId)
        }
    }
}
