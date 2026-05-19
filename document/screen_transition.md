# 画面遷移図：白雪姫のアップルルーレット

**バージョン：** 2.0  
**作成日：** 2026年5月10日  
**最終更新：** 2026年5月19日

---

## 画面一覧

| 画面名 | フェイズ | 説明 |
|-------|---------|------|
| スタート画面 | 準備 | ユーザー名入力・ルーム選択 |
| ルーム作成画面 | 準備 | ゲーム設定（毒リンゴ・役職・カード枚数） |
| ルーム参加画面 | 準備 | ルームコード入力 |
| ルーム復帰画面 | 準備 | ルームコード入力（既存プレイヤーの再接続用） |
| ルーム待機画面 | 準備 | 円形座席表示・ドラッグ並び替え・ゲーム開始待ち |
| ゲームメイン画面 | ゲーム | ストーリーフェイズ / 最後の手番フェイズ |

> **補足：** ストーリーフェイズと最後の手番フェイズは同一画面。山札が残り1枚になると `PHASE_CHANGED { newPhase: "LAST_TURN" }` を受信し、フェイズバーのテキストが「ストーリーフェイズ」→「最後の手番フェイズ」に変わるのみで専用画面への遷移はない。最後の手番フェイズ突入時にはオーバーレイ通知も表示される。
| エンディング画面 | ゲーム | 女王特権処理 / 全員リンゴ公開 |
| 勝敗画面 | 終了 | 勝利陣営・役職公開・再ゲーム選択 |

---

## 遷移一覧

| 遷移元 | 遷移先 | 種別 | トリガー |
|-------|-------|------|---------|
| スタート画面 | ルーム作成画面 | 通常 | 「部屋を作る」ボタン押下 |
| スタート画面 | ルーム参加画面 | 通常 | 「部屋に入る」ボタン押下 |
| スタート画面 | ルーム復帰画面 | 通常 | 「部屋に復帰する」ボタン押下 |
| ルーム作成画面 | ルーム待機画面 | 通常 | ルーム作成完了 |
| ルーム作成画面 | スタート画面 | 戻り | 「戻る」ボタン押下 |
| ルーム参加画面 | ルーム待機画面 | 通常 | ルームコード入力・参加成功 |
| ルーム参加画面 | スタート画面 | 戻り | 「戻る」ボタン押下 |
| ルーム復帰画面 | ゲームメイン画面 | 通常 | 復帰成功（ルームがゲーム中の場合） |
| ルーム復帰画面 | ルーム待機画面 | 通常 | 復帰成功（ルームが待機中の場合） |
| ルーム復帰画面 | スタート画面 | 戻り | 「戻る」ボタン押下 |
| ルーム待機画面 | ゲームメイン画面 | 通常 | ホストがゲーム開始ボタン押下 |
| ルーム待機画面 | スタート画面 | 戻り | 「ルームから抜ける」押下（ホスト＝解散 / ゲスト＝退出） |
| ルーム待機画面 | スタート画面 | **強制** | **`ROOM_DISBANDED` 受信後「ホームへ戻る」ボタン押下** |
| ゲームメイン画面 | ゲームメイン画面（内部状態変化） | 内部 | 山札が残り1枚になると最後の手番フェイズへ移行（フェイズバーのテキスト変更＋オーバーレイ通知・画面遷移なし） |
| ゲームメイン画面 | エンディング画面 | 通常 | 最後の手番フェイズの一周完了（開始プレイヤーの手番終了後） |
| ゲームメイン画面 | 勝敗画面 | **強制** | **白雪姫が1分間再接続しなかった場合（エンディング画面をスキップ）** |
| エンディング画面 | 勝敗画面 | 通常 | 勝敗判定完了 |
| 勝敗画面 | ルーム待機画面 | 戻り | 「もう一度遊ぶ」押下（ホストのみ） |
| 勝敗画面 | スタート画面 | 戻り | 「ルームから抜ける」押下 |

## 補足：画面内の動的コンテンツ

画面遷移を伴わないが、受信イベントによってコンテンツが動的に切り替わる主要なケース。

| 画面 | イベント | 変化内容 |
|-----|---------|---------|
| ルーム待機 | `PLAYER_JOINED` | 座席表に新プレイヤーが追加される |
| ルーム待機 | `PLAYER_LEFT` | 該当プレイヤーの席が空席に戻る |
| ルーム待機 | `SEATS_SWAPPED` | 座席表の配置が入れ替わる |
| ルーム待機 | `DRAG_SEAT_UPDATE` | ドラッグ中の席にハイライト表示 |
| ルーム待機 | `ROOM_DISBANDED` | 解散通知オーバーレイが表示される |
| ゲームメイン | `PHASE_CHANGED { newPhase: "LAST_TURN" }` | フェイズバー「ストーリーフェイズ」→「最後の手番フェイズ」＋オーバーレイ通知 |
| ゲームメイン | `PHASE_CHANGED { newPhase: "ENDING_QUEEN" }` | オーバーレイ通知「👑 エンディング: 女王の特権」 |
| ゲームメイン | `TURN_CHANGED` | 手番インジケーター更新、カウントダウンタイマー開始 |
| ゲームメイン | `NOTIFY_THINK_TIME` | ログに「XXX が長考を使用しました」＋タイマーに+2分加算 |
| ゲームメイン | `REQUEST_PREFERENCE` | 下部エリアが好み回答UIに切り替わる |
| ゲームメイン | `NOTIFY_USE_CARD`（質問カード） | 待機中プレイヤーに「🔮 XXX が YYY に質問中」表示 |
| ゲームメイン | `NOTIFY_PLAYER_DIED` | 該当プレイヤーカードが死亡表示（透過＋骸骨）に変わる |
| ゲームメイン | `NOTIFY_GRAY_ABILITY_ACTIVATED` | 該当プレイヤーの選択画面に「トレード不可」バッジが表示される |
| ゲームメイン | `NOTIFY_LIGHT_ABILITY_ACTIVATED` | ライブテキストに能力発動と交換内容を表示。プレイヤー選択画面は②手札の交換と同じUIで2名選択式（チェックボックス） |
| ゲームメイン | `GAME_RESULT { reason: "SNOW_WHITE_DISCONNECTED" }` | **エンディング画面を経由せず即座に勝敗画面（強制終了パターン）へ遷移** |
| 勝敗画面 | `NOTIFY_REMATCH_STARTING` | **ルーム待機画面へ遷移**（ホストの `REMATCH_REQUEST` 送信後に全員が受信） |

---

## 遷移図（SVG）

<svg width="100%" viewBox="0 0 720 800" role="img">
<title>白雪姫のアップルルーレット 画面遷移図</title>
<desc>スタート画面からゲーム終了まで、8つの画面とその遷移を示す図</desc>
<defs>
  <marker id="arr" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
    <path d="M2 1L8 5L2 9" fill="none" stroke="#555" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
  </marker>
</defs>

<!-- スタート画面 -->
<rect x="260" y="30" width="200" height="56" rx="8" fill="#E1F5EE" stroke="#0F6E56" stroke-width="0.5"/>
<text font-family="sans-serif" font-size="14" font-weight="500" fill="#085041" x="360" y="51" text-anchor="middle" dominant-baseline="central">スタート画面</text>
<text font-family="sans-serif" font-size="12" fill="#0F6E56" x="360" y="71" text-anchor="middle" dominant-baseline="central">ユーザー名入力</text>

<!-- ルーム作成画面 -->
<rect x="40" y="155" width="180" height="44" rx="8" fill="#E1F5EE" stroke="#0F6E56" stroke-width="0.5"/>
<text font-family="sans-serif" font-size="14" font-weight="500" fill="#085041" x="130" y="177" text-anchor="middle" dominant-baseline="central">ルーム作成画面</text>

<!-- ルーム参加画面 -->
<rect x="270" y="155" width="180" height="44" rx="8" fill="#E1F5EE" stroke="#0F6E56" stroke-width="0.5"/>
<text font-family="sans-serif" font-size="14" font-weight="500" fill="#085041" x="360" y="177" text-anchor="middle" dominant-baseline="central">ルーム参加画面</text>

<!-- ルーム復帰画面 -->
<rect x="500" y="155" width="180" height="44" rx="8" fill="#E1F5EE" stroke="#0F6E56" stroke-width="0.5"/>
<text font-family="sans-serif" font-size="14" font-weight="500" fill="#085041" x="590" y="177" text-anchor="middle" dominant-baseline="central">ルーム復帰画面</text>

<!-- ルーム待機画面 -->
<rect x="260" y="275" width="200" height="56" rx="8" fill="#E1F5EE" stroke="#0F6E56" stroke-width="0.5"/>
<text font-family="sans-serif" font-size="14" font-weight="500" fill="#085041" x="360" y="296" text-anchor="middle" dominant-baseline="central">ルーム待機画面</text>
<text font-family="sans-serif" font-size="12" fill="#0F6E56" x="360" y="316" text-anchor="middle" dominant-baseline="central">円形座席・ゲーム開始待ち</text>

<!-- ゲームメイン画面 -->
<rect x="250" y="410" width="220" height="56" rx="8" fill="#EEEDFE" stroke="#534AB7" stroke-width="0.5"/>
<text font-family="sans-serif" font-size="14" font-weight="500" fill="#26215C" x="360" y="431" text-anchor="middle" dominant-baseline="central">ゲームメイン画面</text>
<text font-family="sans-serif" font-size="12" fill="#534AB7" x="360" y="451" text-anchor="middle" dominant-baseline="central">ストーリー / 最後の手番</text>

<!-- エンディング画面 -->
<rect x="250" y="540" width="220" height="56" rx="8" fill="#EEEDFE" stroke="#534AB7" stroke-width="0.5"/>
<text font-family="sans-serif" font-size="14" font-weight="500" fill="#26215C" x="360" y="561" text-anchor="middle" dominant-baseline="central">エンディング画面</text>
<text font-family="sans-serif" font-size="12" fill="#534AB7" x="360" y="581" text-anchor="middle" dominant-baseline="central">女王特権 / リンゴ公開</text>

<!-- 勝敗画面 -->
<rect x="260" y="670" width="200" height="56" rx="8" fill="#FAEEDA" stroke="#BA7517" stroke-width="0.5"/>
<text font-family="sans-serif" font-size="14" font-weight="500" fill="#412402" x="360" y="691" text-anchor="middle" dominant-baseline="central">勝敗画面</text>
<text font-family="sans-serif" font-size="12" fill="#854F0B" x="360" y="711" text-anchor="middle" dominant-baseline="central">役職公開 / 勝利陣営</text>

<!-- 前向き遷移 -->
<path d="M 280 86 L 130 86 L 130 155" fill="none" stroke="#555" stroke-width="1" opacity="0.6" marker-end="url(#arr)"/>
<text font-family="sans-serif" font-size="11" fill="#555" x="195" y="100" text-anchor="middle">部屋を作る</text>
<path d="M 360 86 L 360 155" fill="none" stroke="#555" stroke-width="1" opacity="0.6" marker-end="url(#arr)"/>
<text font-family="sans-serif" font-size="11" fill="#555" x="380" y="125" text-anchor="start">部屋に入る</text>
<path d="M 440 86 L 590 86 L 590 155" fill="none" stroke="#555" stroke-width="1" opacity="0.6" marker-end="url(#arr)"/>
<text font-family="sans-serif" font-size="11" fill="#555" x="520" y="100" text-anchor="middle">部屋に復帰する</text>

<path d="M 130 199 L 130 303 L 260 303" fill="none" stroke="#555" stroke-width="1" opacity="0.6" marker-end="url(#arr)"/>
<path d="M 360 199 L 360 275" fill="none" stroke="#555" stroke-width="1" opacity="0.6" marker-end="url(#arr)"/>
<path d="M 590 199 L 590 303 L 460 303" fill="none" stroke="#555" stroke-width="1" opacity="0.6" marker-end="url(#arr)"/>

<path d="M 360 331 L 360 410" fill="none" stroke="#555" stroke-width="1" opacity="0.6" marker-end="url(#arr)"/>
<text font-family="sans-serif" font-size="11" fill="#555" x="376" y="375" text-anchor="start">ゲーム開始</text>
<path d="M 360 466 L 360 540" fill="none" stroke="#555" stroke-width="1" opacity="0.6" marker-end="url(#arr)"/>
<text font-family="sans-serif" font-size="11" fill="#555" x="376" y="507" text-anchor="start">一周完了</text>
<path d="M 360 596 L 360 670" fill="none" stroke="#555" stroke-width="1" opacity="0.6" marker-end="url(#arr)"/>

<!-- 強制終了遷移（ゲームメイン→勝敗 エンディングスキップ） -->
<defs>
  <marker id="arr-red" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
    <path d="M2 1L8 5L2 9" fill="none" stroke="#E24B4A" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
  </marker>
</defs>
<path d="M 470 466 L 600 466 L 600 698 L 460 698" fill="none" stroke="#E24B4A" stroke-width="1" stroke-dasharray="5 3" opacity="0.7" marker-end="url(#arr-red)"/>
<text font-family="sans-serif" font-size="10" fill="#E24B4A" x="604" y="560" text-anchor="start" opacity="0.9">強制終了</text>
<text font-family="sans-serif" font-size="10" fill="#E24B4A" x="604" y="574" text-anchor="start" opacity="0.9">（白雪姫</text>
<text font-family="sans-serif" font-size="10" fill="#E24B4A" x="604" y="588" text-anchor="start" opacity="0.9">1分未復帰）</text>

<!-- 復帰画面→ゲームメイン（ゲーム中復帰） -->
<path d="M 680 199 L 680 438 L 470 438" fill="none" stroke="#555" stroke-width="1" stroke-dasharray="3 2" opacity="0.6" marker-end="url(#arr)"/>
<text font-family="sans-serif" font-size="10" fill="#555" x="684" y="320" text-anchor="start">ゲーム中復帰</text>

<!-- 戻り遷移（破線） -->
<path d="M 260 698 L 22 698 L 22 315 L 260 315" fill="none" stroke="#555" stroke-width="1" stroke-dasharray="5 3" opacity="0.55" marker-end="url(#arr)"/>
<text font-family="sans-serif" font-size="11" fill="#555" x="132" y="720" text-anchor="middle">もう一度遊ぶ</text>
<path d="M 460 698 L 650 698 L 650 58 L 460 58" fill="none" stroke="#555" stroke-width="1" stroke-dasharray="5 3" opacity="0.55" marker-end="url(#arr)"/>
<path d="M 260 290 L 22 290 L 22 58 L 260 58" fill="none" stroke="#555" stroke-width="1" stroke-dasharray="5 3" opacity="0.55" marker-end="url(#arr)"/>
<text font-family="sans-serif" font-size="11" fill="#555" x="132" y="278" text-anchor="middle">ルームから抜ける</text>
<text font-family="sans-serif" font-size="11" fill="#555" x="560" y="720" text-anchor="middle">ルームから抜ける</text>

<!-- 凡例 -->
<line x1="44" y1="775" x2="80" y2="775" stroke="#555" stroke-width="1" opacity="0.7"/>
<text font-family="sans-serif" font-size="12" fill="#555" x="86" y="779">通常遷移</text>
<line x1="164" y1="775" x2="200" y2="775" stroke="#555" stroke-width="1" stroke-dasharray="5 3" opacity="0.7"/>
<text font-family="sans-serif" font-size="12" fill="#555" x="206" y="779">戻り遷移</text>
<line x1="294" y1="775" x2="330" y2="775" stroke="#E24B4A" stroke-width="1" stroke-dasharray="5 3" opacity="0.7"/>
<text font-family="sans-serif" font-size="12" fill="#E24B4A" x="336" y="779">強制終了</text>
<rect x="420" y="770" width="12" height="12" rx="2" fill="#E1F5EE" stroke="#0F6E56" stroke-width="0.5"/>
<text font-family="sans-serif" font-size="12" fill="#555" x="438" y="779">準備フェイズ</text>
<rect x="536" y="770" width="12" height="12" rx="2" fill="#EEEDFE" stroke="#534AB7" stroke-width="0.5"/>
<text font-family="sans-serif" font-size="12" fill="#555" x="554" y="779">ゲームフェイズ</text>
<rect x="652" y="770" width="12" height="12" rx="2" fill="#FAEEDA" stroke="#BA7517" stroke-width="0.5"/>
<text font-family="sans-serif" font-size="12" fill="#555" x="670" y="779">終了フェイズ</text>
</svg>
