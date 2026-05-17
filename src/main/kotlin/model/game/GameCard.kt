package model.game

import model.enums.CardLocation
import model.enums.CardType
import java.util.UUID

data class GameCard(
    val cardId: UUID,
    val cardType: CardType,
    val location: CardLocation,
    val holderPlayerId: UUID? = null
)
