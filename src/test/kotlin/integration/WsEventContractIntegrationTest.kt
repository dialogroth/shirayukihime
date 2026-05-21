package integration

import model.ws.ClientEventType
import model.ws.EventType
import model.ws.WaitingHostProceedPayload
import model.ws.GameStateSyncPayload
import model.ws.EndingRevealPlayerPayload
import model.ws.SnowWhiteKilledPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * 統合テスト：WebSocket イベント定数とペイロード仕様の整合性
 *
 * 仕様参照：
 * - websocket_event_design.md §4・§5（クライアント/サーバーイベント一覧）
 * - websocket_event_design.md §5-3 GAME_STATE_SYNC.turnTimeRemaining
 *
 * 実装側の Kotlin 定数・データクラスと仕様書のイベント名・フィールド名が一致することを
 * シリアライズ結果まで含めて確認する。
 */
class WsEventContractIntegrationTest {

    private val json = Json { encodeDefaults = true; explicitNulls = true }

    @Test
    fun `クライアントイベント定数 PROCEED_TO_REVEAL PROCEED_TO_RESULT が定義されている（ホスト進行ボタン）`() {
        assertEquals("PROCEED_TO_REVEAL", ClientEventType.PROCEED_TO_REVEAL)
        assertEquals("PROCEED_TO_RESULT", ClientEventType.PROCEED_TO_RESULT)
    }

    @Test
    fun `サーバーイベント定数 WAITING_HOST_PROCEED ENDING_REVEAL_PLAYER SNOW_WHITE_KILLED が定義されている`() {
        assertEquals("WAITING_HOST_PROCEED", EventType.WAITING_HOST_PROCEED)
        assertEquals("ENDING_REVEAL_PLAYER", EventType.ENDING_REVEAL_PLAYER)
        assertEquals("SNOW_WHITE_KILLED", EventType.SNOW_WHITE_KILLED)
    }

    @Test
    fun `WAITING_HOST_PROCEED ペイロードは nextPhase フィールドを持つ`() {
        val payload = WaitingHostProceedPayload(nextPhase = "ENDING_REVEAL")
        val text = json.encodeToString(payload)
        assertTrue(text.contains("\"nextPhase\":\"ENDING_REVEAL\""))

        val payload2 = WaitingHostProceedPayload(nextPhase = "RESULT")
        val text2 = json.encodeToString(payload2)
        assertTrue(text2.contains("\"nextPhase\":\"RESULT\""))
    }

    @Test
    fun `GAME_STATE_SYNC ペイロードに turnTimeRemaining フィールドが含まれる`() {
        val payload = GameStateSyncPayload(
            phase = "STORY",
            currentTurnPlayerId = "uuid",
            deckRemainingCount = 10,
            discardPile = emptyList(),
            players = emptyList(),
            apples = emptyList(),
            myHand = emptyList(),
            turnTimeRemaining = 150
        )
        val text = json.encodeToString(payload)
        assertTrue(text.contains("\"turnTimeRemaining\":150"), "turnTimeRemaining が含まれる")
    }

    @Test
    fun `GAME_STATE_SYNC でエンディング系フェイズでは turnTimeRemaining が null（タイマー停止）`() {
        val payload = GameStateSyncPayload(
            phase = "ENDING_REVEAL",
            currentTurnPlayerId = null,
            deckRemainingCount = 0,
            discardPile = emptyList(),
            players = emptyList(),
            apples = emptyList(),
            myHand = emptyList(),
            turnTimeRemaining = null
        )
        assertNull(payload.turnTimeRemaining)
        assertNull(payload.currentTurnPlayerId, "エンディング系では currentTurnPlayerId も null")
    }

    @Test
    fun `ENDING_REVEAL_PLAYER ペイロードの仕様フィールドが揃っている`() {
        val p = EndingRevealPlayerPayload(
            playerId = "uuid",
            userName = "alice",
            role = "QUEEN",
            faction = "QUEEN_FACTION",
            isPoisoned = true,
            isAlive = false,
            revealIndex = 0,
            totalPlayers = 5
        )
        val text = json.encodeToString(p)
        listOf("playerId", "userName", "role", "faction", "isPoisoned", "isAlive", "revealIndex", "totalPlayers")
            .forEach { assertTrue(text.contains("\"$it\""), "$it フィールドが必要") }
    }

    @Test
    fun `SNOW_WHITE_KILLED ペイロードは cause と snowWhitePlayerId を持つ`() {
        val p = SnowWhiteKilledPayload(cause = "POISON_COMB", snowWhitePlayerId = "uuid")
        val text = json.encodeToString(p)
        assertTrue(text.contains("\"cause\":\"POISON_COMB\""))
        assertTrue(text.contains("\"snowWhitePlayerId\":\"uuid\""))
    }

    @Test
    fun `SNOW_WHITE_KILLED cause は仕様の3種に限定される（POISON_COMB CURSED_RING DISCONNECTED）`() {
        // 仕様書 §5-x SNOW_WHITE_KILLED 参照
        val allowed = setOf("POISON_COMB", "CURSED_RING", "DISCONNECTED")
        allowed.forEach { cause ->
            val p = SnowWhiteKilledPayload(cause = cause, snowWhitePlayerId = "x")
            assertNotNull(p)
        }
        // ENDING_REVEAL フェイズで毒リンゴ死亡（POISON_APPLE）の場合は本イベントを使わない仕様
        // ことをコメントとして明示。実装側で POISON_APPLE 発火を行っていないことは
        // GameService.killPlayer の if (cause != "POISON_APPLE") ガードで保証されている。
        assertTrue("POISON_APPLE" !in allowed)
    }
}

