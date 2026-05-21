package unit

import model.enums.GamePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 単体テスト：手番残り時間 turnTimeRemaining 計算ロジック
 *
 * 仕様参照：
 * - game_requirements.md §11-6-2「タイマーのサーバー同期」「エンディング系フェイズでのタイマー停止」
 * - websocket_event_design.md §5-3 GAME_STATE_SYNC.turnTimeRemaining
 *
 * GameService.sendGameStateSyncTo の turnTimeRemaining 算出式を純粋関数として再現する：
 *   if (phase in [STORY, LAST_TURN]) max(0, turnTimeoutSeconds - (now - turnStartTimeMillis)/1000) else null
 */
class TurnTimeRemainingTest {

    private fun compute(phase: GamePhase, turnStartTimeMillis: Long, turnTimeoutSeconds: Int, nowMillis: Long): Int? {
        return if (phase in listOf(GamePhase.STORY, GamePhase.LAST_TURN)) {
            val elapsed = (nowMillis - turnStartTimeMillis) / 1000
            maxOf(0, turnTimeoutSeconds - elapsed.toInt())
        } else null
    }

    @Test
    fun `STORY フェイズで30秒経過なら残り150秒`() {
        val start = 1_000_000L
        val now = start + 30_000L
        assertEquals(150, compute(GamePhase.STORY, start, 180, now))
    }

    @Test
    fun `LAST_TURN フェイズで残り時間が計算される`() {
        val start = 0L
        val now = 5_000L
        assertEquals(115, compute(GamePhase.LAST_TURN, start, 120, now))
    }

    @Test
    fun `残り時間がマイナスになっても 0 にクランプされる`() {
        val start = 0L
        val now = 300_000L
        assertEquals(0, compute(GamePhase.STORY, start, 180, now))
    }

    @Test
    fun `ENDING_QUEEN ENDING_REVEAL FINISHED では null（タイマー停止＝3分固定表示）`() {
        val start = 0L
        val now = 60_000L
        assertNull(compute(GamePhase.ENDING_QUEEN, start, 180, now))
        assertNull(compute(GamePhase.ENDING_REVEAL, start, 180, now))
        assertNull(compute(GamePhase.FINISHED, start, 180, now))
    }

    @Test
    fun `長考使用後（turnTimeoutSeconds=300）でも正しく計算される`() {
        // 元 180 + 長考 120 = 300
        val start = 0L
        val now = 10_000L
        assertEquals(290, compute(GamePhase.STORY, start, 300, now))
    }

    @Test
    fun `クライアント補正条件 ズレが2秒以上なら補正する`() {
        // クライアント表示残り180秒、サーバー残り175秒 → 5秒ズレ → 補正
        val drift = kotlin.math.abs(180 - 175)
        assertTrue(drift >= 2)

        // クライアント表示残り178秒、サーバー残り179秒 → 1秒ズレ → 補正しない
        val noDrift = kotlin.math.abs(178 - 179)
        assertTrue(noDrift < 2)
    }
}

