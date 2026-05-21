# テストガイド

本ディレクトリには「白雪姫のアップルルーレット」プロジェクトの自動テストを格納している。  
仕様書（`document/` 配下）に対する実装の整合性を継続的に検証することを目的とする。

## 構成

```
src/test/kotlin/
├── unit/         単体テスト：純粋関数・データクラスの派生メソッド・分岐ロジック
├── system/       システムテスト：GameStateManager 等のインメモリ層・フェイズ遷移
└── integration/  統合テスト：イベント定数とペイロード契約・HTTPバリデーション
```

## テスト一覧

### 単体テスト（`unit/`）

| ファイル | 検証内容 | 仕様参照 |
|---------|---------|---------|
| `GameStateUnitTest.kt` | `GameState.handOf` / `appleOf` / `alivePlayers` / `currentTurnPlayerId` の派生メソッドとデフォルト値（`turnStartTimeMillis` / `turnTimeoutSeconds` / `lastTurnPlayersPlayed` 等） | data_model_design.md §4-1, §4-3 |
| `WinFactionLogicTest.kt` | 勝利陣営判定の when 分岐 4 ケース＋ロゼ不在ケース | game_requirements.md §4-2 / data_model_design.md §6-9 |
| `PreferenceAutoAnswerTest.kt` | `REQUEST_PREFERENCE` タイムアウト時の役職別自動回答（ネイビーはランダム） | game_requirements.md §5-1 / websocket_event_design.md §3-4 |
| `TurnTimeRemainingTest.kt` | `turnTimeRemaining` の経過時間計算・`ENDING_*` フェイズでの `null`（タイマー停止＝3:00固定）・長考使用後の計算 | game_requirements.md §11-6-2 / websocket_event_design.md §5-3 |
| `LastTurnRoundCompletionTest.kt` | 最後の手番フェイズ「全生存者プレイ完了」判定（過去のバグ：トリガープレイヤーの最終手番抜けによる早期エンディング移行の再現防止） | game_requirements.md §8-3 |

### システムテスト（`system/`）

| ファイル | 検証内容 |
|---------|---------|
| `GameStateManagerSystemTest.kt` | インメモリ状態の `set` / `get` / `update` / `remove` / `exists` の基本動作、`lastTurnPlayersPlayed` の追加更新 |
| `EndingFlowPhaseOrderSystemTest.kt` | エンディングフェイズの遷移順序（STORY → LAST_TURN → ENDING_QUEEN → ENDING_REVEAL → FINISHED）、女王死亡時の特権スキップ、ENDING_REVEAL での全リンゴ公開・毒リンゴ保持者 `isAlive=false` 更新 |

### 統合テスト（`integration/`）

| ファイル | 検証内容 |
|---------|---------|
| `WsEventContractIntegrationTest.kt` | WebSocket イベント定数とペイロード契約：`PROCEED_TO_REVEAL` / `PROCEED_TO_RESULT` / `WAITING_HOST_PROCEED` / `ENDING_REVEAL_PLAYER` / `SNOW_WHITE_KILLED`・`GAME_STATE_SYNC.turnTimeRemaining` のシリアライズ確認、SNOW_WHITE_KILLED の cause 値域（POISON_COMB / CURSED_RING / DISCONNECTED の3種に限定） |
| `HttpValidationIntegrationTest.kt` | Ktor `testApplication` 上で HTTP ルーティングのバリデーション（ユーザー名 1〜12文字の境界値） |

## 実行方法

```bash
./gradlew test
```

特定のテストのみ：

```bash
./gradlew test --tests "unit.WinFactionLogicTest"
./gradlew test --tests "system.*"
./gradlew test --tests "integration.WsEventContractIntegrationTest"
```

## 範囲外（今後の課題）

以下は実 DB（PostgreSQL）や WebSocket セッションの起動が必要なため、本テストでは網羅していない。  
別途、テスト用 H2 / Testcontainers 等の環境構築を行ったうえで追加することを推奨：

- `RoomService.createRoom` / `joinRoom` の DB トランザクションを通したテスト
- `WebSocketHandler` を介した接続〜ゲーム終了までのフル E2E シナリオ
- `GameService.handleProceedToReveal` / `handleProceedToResult` のホスト権限チェック実フロー
- 接続切断〜1分タイムアウトでの死亡確定／白雪姫の場合の `SNOW_WHITE_KILLED` 強制終了
- アップルルーレットの座席ローテーション（死亡者除外）

これらは現在ソースコードに実装済みで動作確認済（手動テスト・本番動作）であり、自動テスト化の優先度は中程度。

