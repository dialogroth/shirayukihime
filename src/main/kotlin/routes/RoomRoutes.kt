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

/**
 * ユーザー名のバリデーション。
 * - 1〜12文字
 * - HTMLタグ等に使われる文字（< > & " ' `）を含まない
 * - 制御文字を含まない
 *
 * @return エラーメッセージ。問題なければ null。
 */
private fun validateUserName(name: String): String? {
    if (name.isBlank() || name.length > 12)
        return "ユーザー名は1〜12文字で入力してください"
    if (name.any { it.isISOControl() })
        return "ユーザー名に使用できない制御文字が含まれています"
    if (name.any { it in "<>&\"'`" })
        return "ユーザー名に使用できない記号が含まれています（< > & \" ' ` は使用不可）"
    return null
}

fun Route.roomRoutes(
    roomService: RoomService,
    roomRepository: RoomRepository,
    playerRepository: PlayerRepository
) {
    route("/api/rooms") {

        post {
            val req = call.receive<CreateRoomRequest>()

            validateUserName(req.userName)?.let {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(it))
            }

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

                validateUserName(req.userName)?.let {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(it))
                }

                val result = runCatching {
                    roomService.joinRoom(roomCode, req.userName)
                }.getOrElse { e ->
                    val (status, msg) = when (e.message) {
                        "ROOM_NOT_FOUND" -> HttpStatusCode.NotFound to "ルームが見つかりません"
                        "DUPLICATE_USERNAME" -> HttpStatusCode.Conflict to "そのユーザー名は既に使用されています"
                        "ROOM_FULL" -> HttpStatusCode.Conflict to "満席です"
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

            post("/rejoin") {
                val roomCode = call.parameters["roomCode"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("ルームコードが必要です"))
                val req = call.receive<RejoinRoomRequest>()

                if (req.userName.isBlank())
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("ユーザー名を入力してください"))

                val room = roomRepository.findByCode(roomCode)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("ルームが見つかりません"))

                val players = playerRepository.findByRoomId(room.id)
                val player = players.find { it.userName == req.userName }
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("そのユーザー名のプレイヤーが見つかりません"))

                call.respond(HttpStatusCode.OK, RejoinRoomResponse(
                    roomId = room.id.toString(),
                    playerId = player.id.toString(),
                    roomCode = room.roomCode,
                    status = room.status.name
                ))
            }
        }
    }
}
