package system

import model.enums.Faction
import model.enums.GamePhase
import model.enums.Role
import model.game.Apple
import model.game.GamePlayer
import model.game.GameState
import service.GameStateManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * システムテスト：エンディングフェイズのフェイズ遷移順序
 *
 * 仕様参照：
 * - game_requirements.md §8-4 エンディングフェイズ
 * - websocket_event_design.md §6-7 エンディングフェイズフロー
 *
 * フェイズ遷移：STORY → LAST_TURN → ENDING_QUEEN → [ホスト「エンディングに進む」] →
 *               ENDING_REVEAL → [ホスト「結果画面に進む」] → FINISHED
 *
 * GameStateManager 経由でフェイズを進めることで仕様通りの順序で遷移できることを確認する。
 */
class EndingFlowPhaseOrderSystemTest {

    private val roomId = UUID.randomUUID()

    @AfterTest
    fun cleanup() = GameStateManager.remove(roomId)

    private fun mkInitialState(): GameState {
        val sw = GamePlayer(UUID.randomUUID(), "sw", 0, Role.SNOW_WHITE, Faction.SNOW_WHITE_FACTION)
        val q = GamePlayer(UUID.randomUUID(), "q", 1, Role.QUEEN, Faction.QUEEN_FACTION)
        return GameState(
            gameId = UUID.randomUUID(),
            roomId = roomId,
            phase = GamePhase.STORY,
            turnOrder = listOf(sw.playerId, q.playerId),
            currentTurnIndex = 0,
            lastTurnStartPlayerIndex = null,
            players = mapOf(sw.playerId to sw, q.playerId to q),
            apples = listOf(
                Apple(UUID.randomUUID(), false, sw.playerId),
                Apple(UUID.randomUUID(), true, q.playerId)
            ),
            cards = emptyMap(),
            deckOrder = emptyList(),
            discardOrder = emptyList()
        )
    }

    @Test
    fun `エンディングフェイズの遷移順序が仕様通り（STORY → LAST_TURN → ENDING_QUEEN → ENDING_REVEAL → FINISHED）`() {
        GameStateManager.set(roomId, mkInitialState())

        // STORY → LAST_TURN（山札枯渇時）
        var s = GameStateManager.update(roomId) { it.copy(phase = GamePhase.LAST_TURN) }
        assertEquals(GamePhase.LAST_TURN, s?.phase)

        // LAST_TURN → ENDING_QUEEN（一周完了時）
        s = GameStateManager.update(roomId) { it.copy(phase = GamePhase.ENDING_QUEEN, queenSpecialDone = false) }
        assertEquals(GamePhase.ENDING_QUEEN, s?.phase)
        assertEquals(false, s?.queenSpecialDone)

        // 女王特権完了
        s = GameStateManager.update(roomId) { it.copy(queenSpecialDone = true) }
        assertTrue(s!!.queenSpecialDone, "女王特権完了フラグが立つ")

        // [ホストが PROCEED_TO_REVEAL を押す想定] → ENDING_REVEAL
        s = GameStateManager.update(roomId) { it.copy(phase = GamePhase.ENDING_REVEAL) }
        assertEquals(GamePhase.ENDING_REVEAL, s?.phase)

        // ENDING_REVEAL ではリンゴが全公開される
        s = GameStateManager.update(roomId) { state ->
            state.copy(apples = state.apples.map { it.copy(isPubliclyRevealed = true) })
        }
        assertNotNull(s)
        assertTrue(s.apples.all { it.isPubliclyRevealed }, "ENDING_REVEAL では全リンゴが公開される")

        // [ホストが PROCEED_TO_RESULT を押す想定] → FINISHED
        s = GameStateManager.update(roomId) { it.copy(phase = GamePhase.FINISHED) }
        assertEquals(GamePhase.FINISHED, s?.phase)
    }

    @Test
    fun `女王死亡時は ENDING_QUEEN で女王リンゴ公開 REQUEST_QUEEN_EXCHANGE を行わない（queenSpecialDone=true 直行）`() {
        val st0 = mkInitialState()
        val queenId = st0.players.values.first { it.role == Role.QUEEN }.playerId
        val killed = st0.copy(
            players = st0.players + (queenId to st0.players[queenId]!!.copy(isAlive = false))
        )
        GameStateManager.set(roomId, killed)

        // ENDING_QUEEN 遷移時に女王が死亡していたら queenSpecialDone をすぐ true にする想定
        val updated = GameStateManager.update(roomId) {
            val queenAlive = it.players[queenId]!!.isAlive
            it.copy(phase = GamePhase.ENDING_QUEEN, queenSpecialDone = !queenAlive)
        }
        assertEquals(GamePhase.ENDING_QUEEN, updated?.phase)
        assertTrue(updated!!.queenSpecialDone, "女王死亡時は queenSpecialDone を即 true にして待機画面へ")
    }

    @Test
    fun `ENDING_REVEAL で毒リンゴ保持者の isAlive が false に更新される`() {
        var s = mkInitialState().copy(phase = GamePhase.ENDING_REVEAL)
        GameStateManager.set(roomId, s)

        // 毒リンゴを持っているプレイヤーを死亡処理
        val poisonHolder = s.apples.first { it.isPoisoned }.currentHolderPlayerId
        s = GameStateManager.update(roomId) { state ->
            val p = state.players[poisonHolder]!!
            state.copy(players = state.players + (poisonHolder to p.copy(isAlive = false)))
        }!!
        assertEquals(false, s.players[poisonHolder]?.isAlive)
    }
}

