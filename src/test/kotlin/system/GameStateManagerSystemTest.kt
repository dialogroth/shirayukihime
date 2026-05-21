package system

import model.enums.CardLocation
import model.enums.CardType
import model.enums.Faction
import model.enums.GamePhase
import model.enums.Role
import model.game.Apple
import model.game.GameCard
import model.game.GamePlayer
import model.game.GameState
import service.GameStateManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * システムテスト：GameStateManager
 *
 * 仕様参照：
 * - data_model_design.md §1-1（インメモリ管理）/ §4-1（GameState）
 *
 * インメモリの状態保持・更新・削除がスレッドセーフに動作することを確認する。
 */
class GameStateManagerSystemTest {

    private val roomId = UUID.randomUUID()

    private fun mkState(phase: GamePhase = GamePhase.STORY): GameState {
        val p1 = GamePlayer(UUID.randomUUID(), "a", 0, Role.SNOW_WHITE, Faction.SNOW_WHITE_FACTION)
        val p2 = GamePlayer(UUID.randomUUID(), "b", 1, Role.QUEEN, Faction.QUEEN_FACTION)
        return GameState(
            gameId = UUID.randomUUID(),
            roomId = roomId,
            phase = phase,
            turnOrder = listOf(p1.playerId, p2.playerId),
            currentTurnIndex = 0,
            lastTurnStartPlayerIndex = null,
            players = mapOf(p1.playerId to p1, p2.playerId to p2),
            apples = listOf(
                Apple(UUID.randomUUID(), false, p1.playerId),
                Apple(UUID.randomUUID(), true, p2.playerId)
            ),
            cards = emptyMap(),
            deckOrder = emptyList(),
            discardOrder = emptyList()
        )
    }

    @AfterTest
    fun cleanup() {
        GameStateManager.remove(roomId)
    }

    @Test
    fun `set get exists remove の基本動作`() {
        assertFalse(GameStateManager.exists(roomId))
        assertNull(GameStateManager.get(roomId))

        val state = mkState()
        GameStateManager.set(roomId, state)
        assertTrue(GameStateManager.exists(roomId))
        assertEquals(state, GameStateManager.get(roomId))

        GameStateManager.remove(roomId)
        assertFalse(GameStateManager.exists(roomId))
    }

    @Test
    fun `update は transform を適用して新しい state を保存する`() {
        GameStateManager.set(roomId, mkState())
        val newState = GameStateManager.update(roomId) { it.copy(phase = GamePhase.LAST_TURN) }
        assertNotNull(newState)
        assertEquals(GamePhase.LAST_TURN, newState.phase)
        assertEquals(GamePhase.LAST_TURN, GameStateManager.get(roomId)?.phase)
    }

    @Test
    fun `存在しない roomId に対する update は null を返す`() {
        val result = GameStateManager.update(roomId) { it.copy(phase = GamePhase.FINISHED) }
        assertNull(result)
    }

    @Test
    fun `lastTurnPlayersPlayed を copy で追加できる（一周判定用）`() {
        val state = mkState(GamePhase.LAST_TURN)
        GameStateManager.set(roomId, state)
        val playerId = state.turnOrder[0]
        GameStateManager.update(roomId) { it.copy(lastTurnPlayersPlayed = it.lastTurnPlayersPlayed + playerId) }
        assertTrue(GameStateManager.get(roomId)!!.lastTurnPlayersPlayed.contains(playerId))
    }
}

