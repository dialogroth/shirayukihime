package model.ws

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ClientMessage(
    val type: String,
    val payload: JsonObject = JsonObject(emptyMap())
)

object ClientEventType {
    const val GAME_START = "GAME_START"
    const val REMATCH_REQUEST = "REMATCH_REQUEST"
    const val ACTION_DRAW_CARD = "ACTION_DRAW_CARD"
    const val ACTION_USE_CARD = "ACTION_USE_CARD"
    const val ACTION_DISCARD_CARD = "ACTION_DISCARD_CARD"
    const val ACTION_EXCHANGE_HAND = "ACTION_EXCHANGE_HAND"
    const val ACTION_EXCHANGE_APPLE = "ACTION_EXCHANGE_APPLE"
    const val ACTION_CHECK_OWN_APPLE = "ACTION_CHECK_OWN_APPLE"
    const val ACTION_USE_ABILITY = "ACTION_USE_ABILITY"
    const val RESPONSE_PREFERENCE = "RESPONSE_PREFERENCE"
    const val RESPONSE_QUEEN_EXCHANGE = "RESPONSE_QUEEN_EXCHANGE"
}
