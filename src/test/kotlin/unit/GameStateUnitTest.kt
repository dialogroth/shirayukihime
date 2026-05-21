package unit

import model.enums.CardLocation
import model.enums.CardType
import model.enums.Faction
import model.enums.GamePhase
import model.enums.Role
import model.game.Apple
import model.game.GameCard
import model.game.GamePlayer
import model.game.GameState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 単体テスト：GameState の派生メソッド（handOf / appleOf / alivePlayers / currentTurnPlayerId）
 *
 * 仕様参照：
 * - data_model_design.md §4-1（GameState、手札の取得方法）
 * - data_model_design.md §4-3（Apple）
 */
class GameStateUnitTest {

    private fun mkPlayer(name: String, seat: Int, role: Role = Role.BROWN, alive: Boolean = true): GamePlayer {
        val faction = when (role) {
            Role.SNOW_WHITE, Role.GREEN, Role.BROWN, Role.GRAY, Role.LIGHT -> Faction.SNOW_WHITE_FACTION
            Role.QUEEN, Role.BLACK, Role.NAVY -> Faction.QUEEN_FACTION
            Role.ROSE -> Faction.THIRD_FACTION
        }
        return GamePlayer(UUID.randomUUID(), name, seat, role, faction, isAlive = alive)
    }

    private fun mkState(players: List<GamePlayer>, cards: List<GameCard> = emptyList(), apples: List<Apple> = emptyList()): GameState {
        val playersMap = players.associateBy { it.playerId }
        val cardsMap = cards.associateBy { it.cardId }
        return GameState(
            gameId = UUID.randomUUID(),
            roomId = UUID.randomUUID(),
            phase = GamePhase.STORY,
            turnOrder = players.sortedBy { it.seatOrder }.map { it.playerId },
            currentTurnIndex = 0,
            lastTurnStartPlayerIndex = null,
            players = playersMap,
            apples = apples,
            cards = cardsMap,
            deckOrder = emptyList(),
            discardOrder = emptyList()
        )
    }

    @Test
    fun `handOf は holderPlayerId と location=HAND の組み合わせで手札を返す`() {
        val p1 = mkPlayer("a", 0)
        val p2 = mkPlayer("b", 1)
        val card1 = GameCard(UUID.randomUUID(), CardType.KNIFE, CardLocation.HAND, p1.playerId)
        val card2 = GameCard(UUID.randomUUID(), CardType.ROPE, CardLocation.HAND, p2.playerId)
        val card3 = GameCard(UUID.randomUUID(), CardType.ITADAKIMASU, CardLocation.DECK, null)
        val state = mkState(listOf(p1, p2), listOf(card1, card2, card3))

        val handP1 = state.handOf(p1.playerId)
        assertEquals(1, handP1.size)
        assertEquals(CardType.KNIFE, handP1.first().cardType)

        val handP2 = state.handOf(p2.playerId)
        assertEquals(CardType.ROPE, handP2.first().cardType)
    }

    @Test
    fun `appleOf は持ち主プレイヤーIDで紐づくリンゴを返す`() {
        val p1 = mkPlayer("a", 0)
        val p2 = mkPlayer("b", 1)
        val apple1 = Apple(UUID.randomUUID(), isPoisoned = false, currentHolderPlayerId = p1.playerId)
        val apple2 = Apple(UUID.randomUUID(), isPoisoned = true, currentHolderPlayerId = p2.playerId)
        val state = mkState(listOf(p1, p2), apples = listOf(apple1, apple2))

        val a1 = state.appleOf(p1.playerId)
        assertNotNull(a1)
        assertEquals(false, a1.isPoisoned)

        assertEquals(true, state.appleOf(p2.playerId)?.isPoisoned)
        assertNull(state.appleOf(UUID.randomUUID()))
    }

    @Test
    fun `alivePlayers は生存プレイヤーのみを seatOrder 昇順で返す`() {
        val p1 = mkPlayer("a", 2, alive = true)
        val p2 = mkPlayer("b", 0, alive = false)
        val p3 = mkPlayer("c", 1, alive = true)
        val state = mkState(listOf(p1, p2, p3))

        val alive = state.alivePlayers()
        assertEquals(2, alive.size)
        assertEquals("c", alive[0].userName) // seat=1
        assertEquals("a", alive[1].userName) // seat=2
    }

    @Test
    fun `currentTurnPlayerId は turnOrder と currentTurnIndex から取得される`() {
        val p1 = mkPlayer("a", 0)
        val p2 = mkPlayer("b", 1)
        val state = mkState(listOf(p1, p2)).copy(currentTurnIndex = 1)
        assertEquals(p2.playerId, state.currentTurnPlayerId())
    }

    @Test
    fun `GameState 新規フィールドのデフォルト値`() {
        val p = mkPlayer("a", 0)
        val state = mkState(listOf(p))
        assertEquals(emptySet(), state.lastTurnPlayersPlayed)
        assertEquals(false, state.lastTurnTriggerPlayerDone)
        assertEquals(180, state.turnTimeoutSeconds)
        assertEquals(false, state.queenSpecialDone)
        assertTrue(state.turnStartTimeMillis > 0)
    }
}

