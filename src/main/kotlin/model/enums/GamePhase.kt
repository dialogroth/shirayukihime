package model.enums

enum class GamePhase {
    STORY,          // ストーリーフェイズ
    LAST_TURN,      // 最後の手番フェイズ
    ENDING_QUEEN,   // エンディング：女王の特権処理中
    ENDING_REVEAL,  // エンディング：全員リンゴ公開中
    FINISHED        // ゲーム終了
}
