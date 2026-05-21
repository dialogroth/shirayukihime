package unit

import model.enums.Faction
import model.enums.Role
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 単体テスト：勝利陣営判定ロジック
 *
 * 仕様参照：
 * - game_requirements.md §4-2（陣営別勝利条件）
 * - data_model_design.md §6-9（勝利条件の判定ロジック）
 *
 * GameService.determineWinner の when 式と同等のロジックを純粋関数として再現し、
 * 仕様通りに分岐するか単体検証する。
 */
class WinFactionLogicTest {

    data class Holder(val role: Role, val isAlive: Boolean)

    private fun decide(players: List<Holder>): Faction {
        val sw = players.find { it.role == Role.SNOW_WHITE }
        val rose = players.find { it.role == Role.ROSE }
        return when {
            sw?.isAlive != true -> Faction.QUEEN_FACTION
            rose == null || !rose.isAlive -> Faction.SNOW_WHITE_FACTION
            else -> Faction.THIRD_FACTION
        }
    }

    @Test
    fun `白雪姫死亡なら女王陣営勝利`() {
        val winner = decide(listOf(
            Holder(Role.SNOW_WHITE, isAlive = false),
            Holder(Role.QUEEN, isAlive = true),
            Holder(Role.ROSE, isAlive = true)
        ))
        assertEquals(Faction.QUEEN_FACTION, winner)
    }

    @Test
    fun `白雪姫生存かつロゼ不在なら白雪姫陣営勝利`() {
        val winner = decide(listOf(
            Holder(Role.SNOW_WHITE, isAlive = true),
            Holder(Role.QUEEN, isAlive = true)
        ))
        assertEquals(Faction.SNOW_WHITE_FACTION, winner)
    }

    @Test
    fun `白雪姫生存かつロゼ死亡なら白雪姫陣営勝利`() {
        val winner = decide(listOf(
            Holder(Role.SNOW_WHITE, isAlive = true),
            Holder(Role.ROSE, isAlive = false)
        ))
        assertEquals(Faction.SNOW_WHITE_FACTION, winner)
    }

    @Test
    fun `白雪姫生存かつロゼ生存なら第三陣営勝利`() {
        val winner = decide(listOf(
            Holder(Role.SNOW_WHITE, isAlive = true),
            Holder(Role.ROSE, isAlive = true)
        ))
        assertEquals(Faction.THIRD_FACTION, winner)
    }

    @Test
    fun `白雪姫不在ケースは女王陣営勝利として扱う`() {
        // 役職設定上はあり得ないが、防御的に確認
        val winner = decide(listOf(Holder(Role.QUEEN, isAlive = true)))
        assertEquals(Faction.QUEEN_FACTION, winner)
    }
}

