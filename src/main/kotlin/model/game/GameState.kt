package model.game

import model.enums.GamePhase
import java.util.UUID

data class GameState(
    val gameId: UUID,
    val roomId: UUID,
    val phase: GamePhase,
    val turnOrder: List<UUID>,
    val currentTurnIndex: Int,
    val lastTurnStartPlayerIndex: Int?,
    val players: Map<UUID, GamePlayer>,
    val apples: List<Apple>,
    val cards: Map<UUID, GameCard>,
    val deckOrder: List<UUID>,
    val discardOrder: List<UUID>,
    val queenSpecialDone: Boolean = false,
    val lastTurnPlayersPlayed: Set<UUID> = emptySet()
) {
    fun currentTurnPlayerId(): UUID = turnOrder[currentTurnIndex]

    fun alivePlayers(): List<GamePlayer> =
        players.values.filter { it.isAlive }.sortedBy { it.seatOrder }

    fun handOf(playerId: UUID): List<GameCard> =
        cards.values.filter { it.location == model.enums.CardLocation.HAND && it.holderPlayerId == playerId }

    fun appleOf(playerId: UUID): Apple? =
        apples.find { it.currentHolderPlayerId == playerId }
}
