package unit

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 単体テスト：最後の手番フェイズ一周完了判定
 *
 * 仕様参照：
 * - game_requirements.md §8-3「全ての生存プレイヤーが手番を実行済みか で判断する」
 * - data_model_design.md §4-1「最後の手番フェイズの一周判定」
 *
 * 「全ての生存プレイヤー (isAlive=true) の playerId が lastTurnPlayersPlayed に含まれた時点で
 * 一周完了 → ENDING_QUEEN フェイズへ移行する」というロジックを純粋関数で検証する。
 */
class LastTurnRoundCompletionTest {

    private fun isRoundComplete(
        alivePlayerIds: Set<UUID>,
        lastTurnPlayersPlayed: Set<UUID>
    ): Boolean = alivePlayerIds.isNotEmpty() && alivePlayerIds.all { it in lastTurnPlayersPlayed }

    @Test
    fun `全員プレイ済みなら一周完了`() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val c = UUID.randomUUID(); val d = UUID.randomUUID()
        val alive = setOf(a, b, c, d)
        val played = setOf(a, b, c, d)
        assertTrue(isRoundComplete(alive, played))
    }

    @Test
    fun `一人でも未プレイなら一周未完了`() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val c = UUID.randomUUID(); val d = UUID.randomUUID()
        val alive = setOf(a, b, c, d)
        val played = setOf(a, b, c) // d がまだ
        assertFalse(isRoundComplete(alive, played))
    }

    @Test
    fun `死亡者は判定対象から除外される（再現バグ防止）`() {
        // 状況：a,b,c,d でプレイ。c が呪いの指輪で死亡。残りの生存者 a,b,d がプレイ完了で一周完了。
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val c = UUID.randomUUID(); val d = UUID.randomUUID()
        val alive = setOf(a, b, d) // c は除外済
        val played = setOf(a, b, d)
        assertTrue(isRoundComplete(alive, played), "死亡したcは判定対象外")
    }

    @Test
    fun `4人開始 ・トリガープレイヤーの最終手番が抜けていると未完了（ログのバグ再現）`() {
        // 過去ログのバグ：a がトリガーで b,c,d のあと a の最後の手番が来る前にエンディング移行してしまった
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val c = UUID.randomUUID(); val d = UUID.randomUUID()
        val alive = setOf(a, b, c, d)
        val played = setOf(b, c, d) // a の最終手番が抜けている → 一周未完了であるべき
        assertFalse(isRoundComplete(alive, played))
    }

    @Test
    fun `生存者がいない場合 isRoundComplete は false（全滅で勝敗判定へ別経路）`() {
        val played = emptySet<UUID>()
        assertFalse(isRoundComplete(emptySet(), played))
    }
}

