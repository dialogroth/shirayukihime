package model.ws

import kotlinx.serialization.Serializable

@Serializable
data class PlayerSummary(
    val playerId: String,
    val userName: String,
    val seatOrder: Int,
    val isAlive: Boolean,
    val isConnected: Boolean,
    val isRoleRevealed: Boolean,
    val role: String? = null,
    val isProtected: Boolean,
    val skipNextTurn: Boolean,
    val applePreferenceAnswer: Boolean? = null,
    val mushroomPreferenceAnswer: Boolean? = null
)
