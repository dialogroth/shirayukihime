package model.game

import model.enums.Faction
import model.enums.Role
import java.util.UUID

data class GamePlayer(
    val playerId: UUID,
    val userName: String,
    val seatOrder: Int,
    val role: Role,
    val faction: Faction,
    val isAlive: Boolean = true,
    val isConnected: Boolean = true,
    val isRoleRevealed: Boolean = false,
    val grayAbilityUsed: Boolean = false,
    val lightAbilityUsed: Boolean = false,
    val isProtected: Boolean = false,
    val skipNextTurn: Boolean = false,
    val applePreferenceAnswer: Boolean? = null,
    val mushroomPreferenceAnswer: Boolean? = null,
    val thinkTimeUsed: Boolean = false
)
