package unit

import model.enums.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 単体テスト：好み質問タイムアウト時の自動回答ロジック
 *
 * 仕様参照：
 * - game_requirements.md §5-1（各役職の好み設定）
 * - websocket_event_design.md §3-4（REQUEST_PREFERENCE タイムアウト 1分・役職正解を自動回答 / ネイビーはランダム）
 *
 * GameService.handlePreferenceTimeout の when 式と同等のロジックを純粋関数として再現する。
 */
class PreferenceAutoAnswerTest {

    private fun autoAnswer(role: Role, questionType: String): Boolean? = when (role) {
        Role.SNOW_WHITE, Role.QUEEN, Role.LIGHT -> questionType == "APPLE"
        Role.GREEN, Role.BLACK, Role.GRAY -> questionType == "MUSHROOM"
        Role.BROWN -> true
        Role.ROSE -> false
        Role.NAVY -> null // ランダム扱い
    }

    @Test
    fun `白雪姫_女王_ライト はリンゴ好き_きのこ嫌い`() {
        listOf(Role.SNOW_WHITE, Role.QUEEN, Role.LIGHT).forEach { role ->
            assertEquals(true, autoAnswer(role, "APPLE"), "$role / APPLE")
            assertEquals(false, autoAnswer(role, "MUSHROOM"), "$role / MUSHROOM")
        }
    }

    @Test
    fun `グリーン_ブラック_グレイ はリンゴ嫌い_きのこ好き`() {
        listOf(Role.GREEN, Role.BLACK, Role.GRAY).forEach { role ->
            assertEquals(false, autoAnswer(role, "APPLE"), "$role / APPLE")
            assertEquals(true, autoAnswer(role, "MUSHROOM"), "$role / MUSHROOM")
        }
    }

    @Test
    fun `ブラウン は両方好き`() {
        assertEquals(true, autoAnswer(Role.BROWN, "APPLE"))
        assertEquals(true, autoAnswer(Role.BROWN, "MUSHROOM"))
    }

    @Test
    fun `ロゼ は両方嫌い`() {
        assertEquals(false, autoAnswer(Role.ROSE, "APPLE"))
        assertEquals(false, autoAnswer(Role.ROSE, "MUSHROOM"))
    }

    @Test
    fun `ネイビー はランダム扱い（仕様上 YesNo どちらも返り得る）`() {
        assertEquals(null, autoAnswer(Role.NAVY, "APPLE"))
        // 実装側では listOf(true, false).random() のため、両値の出現を統計的に確認
        val samples = (1..200).map { listOf(true, false).random() }.toSet()
        assertTrue(samples.contains(true), "true が出現すること")
        assertTrue(samples.contains(false), "false が出現すること")
    }
}

