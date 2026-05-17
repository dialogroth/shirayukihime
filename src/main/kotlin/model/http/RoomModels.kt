package model.http

import kotlinx.serialization.Serializable

@Serializable
data class CardSettingRequest(
    val cardType: String,
    val count: Int
)

@Serializable
data class CreateRoomRequest(
    val userName: String,
    val poisonAppleCount: Int,
    val roles: List<String>,
    val cardSettings: List<CardSettingRequest> = emptyList()
)

@Serializable
data class CreateRoomResponse(
    val roomId: String,
    val roomCode: String,
    val playerId: String
)

@Serializable
data class JoinRoomRequest(val userName: String)

@Serializable
data class JoinRoomResponse(
    val roomId: String,
    val playerId: String,
    val roomCode: String
)

@Serializable
data class RoomInfoResponse(
    val roomId: String,
    val roomCode: String,
    val status: String,
    val players: List<PlayerInfo>,
    val settings: RoomSettingsInfo
)

@Serializable
data class PlayerInfo(
    val playerId: String,
    val userName: String,
    val seatOrder: Int,
    val isHost: Boolean,
    val isConnected: Boolean
)

@Serializable
data class RoomSettingsInfo(
    val poisonAppleCount: Int,
    val roles: List<String>,
    val cardSettings: List<CardSettingRequest>
)

@Serializable
data class ErrorResponse(val error: String)
