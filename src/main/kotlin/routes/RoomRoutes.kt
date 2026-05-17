package routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import model.enums.CardType
import model.http.*
import repository.PlayerRepository
import repository.RoomRepository
import service.RoomService

fun Route.roomRoutes(
    roomService: RoomService,
    roomRepository: RoomRepository,
    playerRepository: PlayerRepository
) {
    route("/api/rooms") {

        post {
            val req = call.receive<CreateRoomRequest>()

            if (req.userName.isBlank() || req.userName.length > 12)
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("ユーザー名は1〜12文字で入力してください"))

            val cardSettings = req.cardSettings.mapNotNull { cs ->
                runCatching { CardType.valueOf(cs.cardType) to cs.count }.getOrNull()
            }

            val result = runCatching {
                roomService.createRoom(req.userName, req.poisonAppleCount, req.roles, cardSettings)
            }.getOrElse {
                return@post call.respond(HttpStatusCode.InternalServerError, ErrorResponse(it.message ?: "ルーム作成に失敗しました"))
            }

            call.respond(HttpStatusCode.Created, CreateRoomResponse(
                roomId = result.roomId.toString(),
                roomCode = result.roomCode,
                playerId = result.playerId.toString()
            ))
        }

        route("/{roomCode}") {

            get {
                val roomCode = call.parameters["roomCode"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("ルームコードが必要です"))

                val room = roomRepository.findByCode(roomCode)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("ルームが見つかりません"))

                val players = playerRepository.findByRoomId(room.id)
                val settings = roomRepository.findSettings(room.id)

                call.respond(RoomInfoResponse(
                    roomId = room.id.toString(),
                    roomCode = room.roomCode,
                    status = room.status.name,
                    players = players.map {
                        PlayerInfo(it.id.toString(), it.userName, it.seatOrder, it.isHost, it.isConnected)
                    },
                    settings = RoomSettingsInfo(
                        poisonAppleCount = settings?.poisonAppleCount ?: 2,
                        roles = settings?.roles ?: emptyList(),
                        cardSettings = settings?.cardSettings?.map { cs ->
                            CardSettingRequest(cs.cardType.name, cs.count)
                        } ?: emptyList()
                    )
                ))
            }

            post("/join") {
                val roomCode = call.parameters["roomCode"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("ルームコードが必要です"))
                val req = call.receive<JoinRoomRequest>()

                if (req.userName.isBlank() || req.userName.length > 12)
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("ユーザー名は1〜12文字で入力してください"))

                val result = runCatching {
                    roomService.joinRoom(roomCode, req.userName)
                }.getOrElse { e ->
                    val (status, msg) = when (e.message) {
                        "ROOM_NOT_FOUND" -> HttpStatusCode.NotFound to "ルームが見つかりません"
                        "DUPLICATE_USERNAME" -> HttpStatusCode.Conflict to "そのユーザー名は既に使用されています"
                        else -> HttpStatusCode.InternalServerError to "参加に失敗しました"
                    }
                    return@post call.respond(status, ErrorResponse(msg))
                }

                call.respond(HttpStatusCode.OK, JoinRoomResponse(
                    roomId = result.roomId.toString(),
                    playerId = result.playerId.toString(),
                    roomCode = result.roomCode
                ))
            }
        }
    }
}
