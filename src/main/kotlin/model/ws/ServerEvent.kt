package model.ws

import kotlinx.serialization.Serializable

// ── 接続・ルーム ──────────────────────────────────────

@Serializable
data class PlayerJoinedPayload(val playerId: String, val userName: String, val seatOrder: Int)

@Serializable
data class PlayerDisconnectedPayload(val playerId: String, val userName: String)

// ── ゲーム開始 ─────────────────────────────────────────

@Serializable
data class GameStartedPayload(
    val gameId: String,
    val players: List<PlayerSummary>,
    val firstTurnPlayerId: String
)

@Serializable
data class CardInfo(val cardId: String, val cardType: String)

@Serializable
data class AppleInfo(val appleId: String, val isPoisoned: Boolean)

@Serializable
data class YourInitialInfoPayload(
    val role: String,
    val faction: String,
    val myApple: AppleInfo,
    val myHand: List<CardInfo>,
    val snowWhitePlayerId: String? = null,
    val poisonAppleHolderIds: List<String>? = null
)

// ── ゲーム状態同期 ────────────────────────────────────

@Serializable
data class GameStateSyncPayload(
    val phase: String,
    val currentTurnPlayerId: String?,
    val deckRemainingCount: Int,
    val discardPile: List<CardInfo>,
    val players: List<PlayerSummary>,
    val apples: List<AppleSummary>,
    val myHand: List<CardInfo>,
    val turnTimeRemaining: Int? = null
)

@Serializable
data class YourAppleStatusPayload(val appleId: String, val isPoisoned: Boolean)

@Serializable
data class PoisonAppleLocation(val appleId: String, val currentHolderPlayerId: String)

@Serializable
data class BlackAppleUpdatePayload(val poisonApples: List<PoisonAppleLocation>)

// ── アクション通知 ────────────────────────────────────

@Serializable
data class NotifyDrawCardPayload(val playerId: String, val deckRemainingCount: Int)

@Serializable
data class NotifyUseCardPayload(val playerId: String, val cardType: String, val params: Map<String, String>)

@Serializable
data class NotifyDiscardCardPayload(val playerId: String, val cardType: String)

@Serializable
data class NotifyThinkTimePayload(val playerId: String, val newTimeoutSeconds: Int)

@Serializable
data class NotifyExchangeHandPayload(val playerIdA: String, val playerIdB: String)

@Serializable
data class NotifyExchangeApplePayload(val playerIdA: String, val playerIdB: String)

@Serializable
data class NotifyApplePubliclyRevealedPayload(
    val appleId: String,
    val holderPlayerId: String,
    val isPoisoned: Boolean
)

@Serializable
data class EndingRevealPlayerPayload(
    val playerId: String,
    val userName: String,
    val role: String,
    val faction: String,
    val isPoisoned: Boolean,
    val isAlive: Boolean,
    val revealIndex: Int,
    val totalPlayers: Int
)

@Serializable
data class SnowWhiteKilledPayload(
    val cause: String,
    val killerPlayerId: String? = null,
    val snowWhitePlayerId: String
)


@Serializable
data class NotifyRoulettePayload(
    val cardType: String,
    val direction: String,
    val steps: Int,
    val excludedPlayerIds: List<String>
)

@Serializable
data class NotifyPreferenceAnsweredPayload(
    val playerId: String,
    val questionType: String,
    val answer: Boolean
)

@Serializable
data class NotifyPlayerDiedPayload(val playerId: String, val cause: String)

@Serializable
data class NotifyPlayerSkippedPayload(val playerId: String)

@Serializable
data class NotifyGrayAbilityActivatedPayload(val playerId: String)

@Serializable
data class NotifyLightAbilityActivatedPayload(
    val playerId: String,
    val role: String,
    val swappedPlayerIdA: String,
    val swappedPlayerIdB: String
)

@Serializable
data class NotifyKnightBlockedPayload(val targetPlayerId: String)

@Serializable
data class NotifyGuardActivatedPayload(val playerId: String)

@Serializable
data class NotifyTimeoutPayload(
    val timeoutType: String,
    val playerId: String,
    val autoAction: String,
    val discardedCardType: String? = null
)

// ── 入力要求 ──────────────────────────────────────────

@Serializable
data class RequestPreferencePayload(val questionType: String, val askedByPlayerId: String)

@Serializable
data class RequestQueenExchangePayload(val availableTargetPlayerIds: List<String>)

// ── フェイズ・ターン ──────────────────────────────────

@Serializable
data class PhaseChangedPayload(val newPhase: String, val triggerPlayerId: String?)

@Serializable
data class TurnChangedPayload(val currentTurnPlayerId: String, val timeoutSeconds: Int)

// ── ゲーム終了 ─────────────────────────────────────────

@Serializable
data class GameResultPlayer(
    val playerId: String,
    val userName: String,
    val role: String,
    val faction: String,
    val isAlive: Boolean,
    val isWinner: Boolean,
    val apple: AppleInfo
)

@Serializable
data class GameResultPayload(
    val winFaction: String,
    val reason: String,
    val players: List<GameResultPlayer>
)

// ── エラー ────────────────────────────────────────────

@Serializable
data class ErrorPayload(val code: String, val message: String)

// ── 再ゲーム ──────────────────────────────────────────

@Serializable
class NotifyRematchStartingPayload

@Serializable
data class WaitingHostProceedPayload(val nextPhase: String)

// ── ホスト権限の移譲 ─────────────────────────────────────
@Serializable
data class HostTransferredPayload(
    val newHostPlayerId: String,
    val newHostUserName: String,
    val previousHostPlayerId: String,
    val reason: String = "DISCONNECTED"
)

// ── 切断中プレイヤーの手番待ち ─────────────────────────────
// 手番が回ってきたプレイヤーが切断中の場合に全員へ配信される。
// 通常の TURN_CHANGED とは別に、行動UIを完全に隠して
// 「{userName} が切断中です。復帰を待っています…」を表示させる。
@Serializable
data class WaitingForDisconnectedPlayerPayload(
    val playerId: String,
    val userName: String,
    val timeoutSeconds: Int = 60
)

object EventType {
    const val PLAYER_JOINED = "PLAYER_JOINED"
    const val PLAYER_DISCONNECTED = "PLAYER_DISCONNECTED"
    const val GAME_STARTED = "GAME_STARTED"
    const val YOUR_INITIAL_INFO = "YOUR_INITIAL_INFO"
    const val GAME_STATE_SYNC = "GAME_STATE_SYNC"
    const val YOUR_APPLE_STATUS = "YOUR_APPLE_STATUS"
    const val BLACK_APPLE_UPDATE = "BLACK_APPLE_UPDATE"
    const val NOTIFY_DRAW_CARD = "NOTIFY_DRAW_CARD"
    const val NOTIFY_USE_CARD = "NOTIFY_USE_CARD"
    const val NOTIFY_DISCARD_CARD = "NOTIFY_DISCARD_CARD"
    const val NOTIFY_EXCHANGE_HAND = "NOTIFY_EXCHANGE_HAND"
    const val NOTIFY_EXCHANGE_APPLE = "NOTIFY_EXCHANGE_APPLE"
    const val NOTIFY_APPLE_PUBLICLY_REVEALED = "NOTIFY_APPLE_PUBLICLY_REVEALED"
    const val ENDING_REVEAL_PLAYER = "ENDING_REVEAL_PLAYER"
    const val SNOW_WHITE_KILLED = "SNOW_WHITE_KILLED"
    const val NOTIFY_ROULETTE = "NOTIFY_ROULETTE"
    const val NOTIFY_PREFERENCE_ANSWERED = "NOTIFY_PREFERENCE_ANSWERED"
    const val NOTIFY_PLAYER_DIED = "NOTIFY_PLAYER_DIED"
    const val NOTIFY_PLAYER_SKIPPED = "NOTIFY_PLAYER_SKIPPED"
    const val NOTIFY_GRAY_ABILITY_ACTIVATED = "NOTIFY_GRAY_ABILITY_ACTIVATED"
    const val NOTIFY_LIGHT_ABILITY_ACTIVATED = "NOTIFY_LIGHT_ABILITY_ACTIVATED"
    const val NOTIFY_KNIGHT_BLOCKED = "NOTIFY_KNIGHT_BLOCKED"
    const val NOTIFY_GUARD_ACTIVATED = "NOTIFY_GUARD_ACTIVATED"
    const val NOTIFY_TIMEOUT = "NOTIFY_TIMEOUT"
    const val NOTIFY_THINK_TIME = "NOTIFY_THINK_TIME"
    const val REQUEST_PREFERENCE = "REQUEST_PREFERENCE"
    const val REQUEST_QUEEN_EXCHANGE = "REQUEST_QUEEN_EXCHANGE"
    const val PHASE_CHANGED = "PHASE_CHANGED"
    const val TURN_CHANGED = "TURN_CHANGED"
    const val GAME_RESULT = "GAME_RESULT"
    const val NOTIFY_REMATCH_STARTING = "NOTIFY_REMATCH_STARTING"
    const val ROOM_DISBANDED = "ROOM_DISBANDED"
    const val PLAYER_LEFT = "PLAYER_LEFT"
    const val SEATS_SWAPPED = "SEATS_SWAPPED"
    const val DRAG_SEAT_UPDATE = "DRAG_SEAT_UPDATE"
    const val WAITING_HOST_PROCEED = "WAITING_HOST_PROCEED"
    const val HOST_TRANSFERRED = "HOST_TRANSFERRED"
    const val WAITING_FOR_DISCONNECTED_PLAYER = "WAITING_FOR_DISCONNECTED_PLAYER"
    const val ERROR = "ERROR"
}
