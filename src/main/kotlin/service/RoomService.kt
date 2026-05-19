package service

import model.enums.CardType
import repository.CardSettingRow
import repository.PlayerRepository
import repository.RoomRepository
import java.util.UUID

class RoomService(
    private val roomRepository: RoomRepository,
    private val playerRepository: PlayerRepository
) {
    companion object {
        private val DEFAULT_CARD_COUNTS = mapOf(
            CardType.APPLE_QUESTION to 5, CardType.MUSHROOM_QUESTION to 5,
            CardType.ITADAKIMASU to 3, CardType.ROULETTE_1 to 1,
            CardType.ROULETTE_2 to 1, CardType.ROULETTE_3 to 1,
            CardType.KNIFE to 1, CardType.CURSED_RING to 1,
            CardType.POISON_COMB to 1, CardType.KNIGHT to 1,
            CardType.GUARD to 1, CardType.ROPE to 1,
            CardType.PRESENT_EXCHANGE to 1
        )
    }

    data class CreateRoomResult(
        val roomId: UUID,
        val roomCode: String,
        val playerId: UUID
    )

    data class JoinRoomResult(
        val roomId: UUID,
        val playerId: UUID,
        val roomCode: String
    )

    fun createRoom(
        userName: String,
        poisonAppleCount: Int,
        roles: List<String>,
        cardSettings: List<Pair<CardType, Int>>
    ): CreateRoomResult {
        val roomCode = generateRoomCode()
        val roomId = roomRepository.create(roomCode)

        roomRepository.saveSettings(roomId, poisonAppleCount, roles)

        val settingsToSave = DEFAULT_CARD_COUNTS.map { (type, default) ->
            val overrideCount = cardSettings.find { it.first == type }?.second ?: default
            CardSettingRow(type, overrideCount)
        }
        roomRepository.saveCardSettings(roomId, settingsToSave)

        val playerId = playerRepository.create(roomId, userName, 0, isHost = true)
        roomRepository.updateHost(roomId, playerId)

        return CreateRoomResult(roomId, roomCode, playerId)
    }

    fun joinRoom(roomCode: String, userName: String): JoinRoomResult {
        val room = roomRepository.findByCode(roomCode)
            ?: error("ROOM_NOT_FOUND")

        if (playerRepository.existsUserNameInRoom(room.id, userName))
            error("DUPLICATE_USERNAME")

        val settings = roomRepository.findSettings(room.id)
        val maxPlayers = settings?.roles?.size ?: Int.MAX_VALUE
        val currentCount = playerRepository.findByRoomId(room.id).size
        if (currentCount >= maxPlayers)
            error("ROOM_FULL")

        val seatOrder = playerRepository.nextSeatOrder(room.id)
        val playerId = playerRepository.create(room.id, userName, seatOrder)

        return JoinRoomResult(room.id, playerId, roomCode)
    }

    fun getRoomInfo(roomCode: String): Triple<UUID, String, String>? {
        val room = roomRepository.findByCode(roomCode) ?: return null
        return Triple(room.id, room.roomCode, room.status.name)
    }

    private fun generateRoomCode(): String {
        return (1..6).map { (0..9).random() }.joinToString("")
    }
}
