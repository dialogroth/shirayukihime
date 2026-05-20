// === State ===
let state = {
  screen: 'start',
  username: '',
  roomId: '',
  roomCode: '',
  playerId: '',
  isHost: false,
  ws: null,
  // Game state
  role: null,
  faction: null,
  myApple: null,
  myHand: [],
  gameState: null,
  initialInfo: null,
  selectedRoles: [],
  pollInterval: null,
  // Timer
  turnTimer: null,
  turnTimeRemaining: 0,
  thinkTimeUsed: false
};

const CARD_NAMES = {
  APPLE_QUESTION: 'リンゴは好き？',
  MUSHROOM_QUESTION: 'きのこは好き？',
  ITADAKIMASU: 'いただきます。',
  ROULETTE_1: 'アップルルーレット！',
  ROULETTE_2: 'アップルルーレット！！',
  ROULETTE_3: 'アップルルーレット！！！',
  KNIFE: '包丁',
  CURSED_RING: '呪いの指輪',
  POISON_COMB: '毒の櫛',
  KNIGHT: '騎士',
  GUARD: 'ガード',
  ROPE: 'ロープ',
  PRESENT_EXCHANGE: 'プレゼント交換'
};

const ROLE_NAMES = {
  SNOW_WHITE: '白雪姫', QUEEN: '女王', GREEN: 'グリーン', BLACK: 'ブラック',
  BROWN: 'ブラウン', GRAY: 'グレイ', NAVY: 'ネイビー', ROSE: 'ロゼ', LIGHT: 'ライト'
};

const FACTION_NAMES = {
  SNOW_WHITE_FACTION: '白雪姫陣営', QUEEN_FACTION: '女王陣営', THIRD_FACTION: '第三陣営'
};

// === Screen navigation ===
function showScreen(name) {
  document.querySelectorAll('.screen').forEach(s => s.classList.remove('active'));
  document.getElementById('screen-' + name).classList.add('active');
  state.screen = name;
}

// === API helpers ===
const API = '/api/rooms';
async function api(method, path, body) {
  const opts = { method, headers: { 'Content-Type': 'application/json' } };
  if (body) opts.body = JSON.stringify(body);
  const res = await fetch(API + path, opts);
  const data = await res.json();
  if (!res.ok) throw new Error(data.message || 'エラーが発生しました');
  return data;
}

// === WebSocket ===
function connectWs() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const url = `${proto}//${location.host}/ws/game?roomId=${state.roomId}&playerId=${state.playerId}`;
  const ws = new WebSocket(url);
  state.ws = ws;

  ws.onopen = () => log('WebSocket接続完了');
  ws.onmessage = (e) => {
    try {
      const msg = JSON.parse(e.data);
      handleServerEvent(msg);
    } catch(err) { console.error('Parse error:', err); }
  };
  ws.onclose = () => log('接続が切れました。リロードしてください。');
  ws.onerror = () => log('接続エラー');
}

function sendEvent(type, payload = {}) {
  if (state.ws && state.ws.readyState === WebSocket.OPEN) {
    state.ws.send(JSON.stringify({ type, payload }));
  } else {
    alert('サーバーに接続されていません');
  }
}

// === Server event handler ===
function handleServerEvent(msg) {
  const { type, payload } = msg;
  log(`← ${type}`);

  switch (type) {
    case 'PLAYER_JOINED':
      pollRoom();
      break;
    case 'PLAYER_DISCONNECTED':
      pollRoom();
      log(`${payload.userName} が切断しました`);
      break;
    case 'ROOM_DISBANDED':
      // ホスト以外に表示
      if (!state.isHost) {
        document.getElementById('room-disbanded-overlay').style.display = 'flex';
      }
      break;
    case 'SEATS_SWAPPED':
      // Re-poll to get updated seat orders
      pollRoom();
      break;
    case 'PLAYER_LEFT':
      pollRoom();
      break;
    case 'DRAG_SEAT_UPDATE':
      // Show drag indicator for other players
      state.dragOverSeat = payload.overSeatIndex !== '' ? parseInt(payload.overSeatIndex) : null;
      renderSeatCircle();
      break;
    case 'GAME_STARTED':
      showScreen('game');
      state.thinkTimeUsed = false;
      updatePhaseLabel('STORY');
      // ルームコードをゲーム画面に表示
      document.getElementById('room-code-label').textContent = `🏠 ${state.roomCode}`;
      // ログをクリア（ゲーム開始時からのみ表示）
      const logEl = document.getElementById('game-log');
      if (logEl) logEl.innerHTML = '';
      if (payload.players) {
        if (!state.gameState) state.gameState = {};
        state.gameState.players = payload.players;
        renderPlayers(payload.players);
      }
      // 手札・リンゴ表示初期化
      renderMyHand();
      renderMyApple();
      break;
    case 'YOUR_INITIAL_INFO':
      handleInitialInfo(payload);
      break;
    case 'GAME_STATE_SYNC':
      handleGameStateSync(payload);
      break;
    case 'TURN_CHANGED':
      handleTurnChanged(payload);
      break;
    case 'PHASE_CHANGED':
      handlePhaseChanged(payload);
      break;
    case 'REQUEST_PREFERENCE':
      showPreferenceUI(payload);
      break;
    case 'REQUEST_QUEEN_EXCHANGE':
      showQueenExchangeUI(payload);
      break;
    case 'YOUR_APPLE_STATUS':
      state.myApple = payload;
      renderMyApple();
      log(`あなたのリンゴ: ${payload.isPoisoned ? '🍎毒' : '🍏安全'}`);
      break;
    case 'BLACK_APPLE_UPDATE':
      log(`毒リンゴ位置が更新されました`);
      if (state.initialInfo) state.initialInfo.poisonAppleHolderIds = payload.poisonApples.map(a => a.currentHolderPlayerId);
      break;
    case 'NOTIFY_DRAW_CARD':
      log(`${getPlayerName(payload.playerId)} がカードを引きました（残り${payload.deckRemainingCount}枚）`);
      break;
    case 'NOTIFY_USE_CARD':
      const cardName = CARD_NAMES[payload.cardType] || payload.cardType;
      const targetName = payload.params?.targetPlayerId ? getPlayerName(payload.params.targetPlayerId) : null;
      if (targetName) {
        log(`${getPlayerName(payload.playerId)} が ${targetName} に「${cardName}」を使用しました`);
      } else {
        log(`${getPlayerName(payload.playerId)} が「${cardName}」を使用しました`);
      }
      // 質問カードの場合、待機中のプレイヤーに状況を表示
      if ((payload.cardType === 'APPLE_QUESTION' || payload.cardType === 'MUSHROOM_QUESTION') && payload.params?.targetPlayerId !== state.playerId) {
        const actionsEl = document.getElementById('game-actions');
        actionsEl.innerHTML = `<div style="text-align:center;color:#aaa;padding:12px;">🔮 ${getPlayerName(payload.playerId)} が ${targetName} に「${cardName}」を聞いています。回答待ち…</div>`;
      }
      break;
    case 'NOTIFY_DISCARD_CARD':
      log(`${getPlayerName(payload.playerId)} が「${CARD_NAMES[payload.cardType] || payload.cardType}」を捨てました`);
      break;
    case 'NOTIFY_EXCHANGE_HAND':
      log(`${getPlayerName(payload.playerIdA)} と ${getPlayerName(payload.playerIdB)} が手札を交換しました`);
      break;
    case 'NOTIFY_EXCHANGE_APPLE':
      log(`${getPlayerName(payload.playerIdA)} と ${getPlayerName(payload.playerIdB)} がリンゴを交換しました`);
      if (payload.playerIdA === state.playerId || payload.playerIdB === state.playerId) {
        state.myApple = null;
        renderMyApple();
      }
      break;
    case 'NOTIFY_APPLE_PUBLICLY_REVEALED':
      log(`${getPlayerName(payload.holderPlayerId)} のリンゴが公開されました: ${payload.isPoisoned ? '🍎毒' : '🍏安全'}`);
      break;
    case 'NOTIFY_ROULETTE':
      log(`アップルルーレット! ${payload.direction === 'CLOCKWISE' ? '時計回り' : '反時計回り'}に${payload.steps}個移動`);
      break;
    case 'NOTIFY_PREFERENCE_ANSWERED':
      const qType = payload.questionType === 'APPLE' ? 'リンゴ' : 'きのこ';
      log(`${getPlayerName(payload.playerId)}: ${qType}${payload.answer ? '好き' : '嫌い'}`);
      break;
    case 'NOTIFY_PLAYER_DIED':
      const cause = { POISON_COMB: '毒の櫛', CURSED_RING: '呪いの指輪', POISON_APPLE: '毒リンゴ', DISCONNECTED: '切断' };
      log(`☠️ ${getPlayerName(payload.playerId)} が死亡しました（原因: ${cause[payload.cause] || payload.cause}）`);
      break;
    case 'NOTIFY_PLAYER_SKIPPED':
      log(`${getPlayerName(payload.playerId)} の手番がスキップされました`);
      break;
    case 'NOTIFY_GRAY_ABILITY_ACTIVATED':
      log(`🛡️ ${getPlayerName(payload.playerId)} がグレイの能力を発動しました`);
      break;
    case 'NOTIFY_LIGHT_ABILITY_ACTIVATED':
      log(`✨ ${getPlayerName(payload.playerId)} がライトの能力を発動し、${getPlayerName(payload.swappedPlayerIdA)} と ${getPlayerName(payload.swappedPlayerIdB)} のリンゴを交換しました`);
      break;
    case 'NOTIFY_KNIGHT_BLOCKED':
      log(`⚔️ 騎士により毒の櫛が無効化されました`);
      break;
    case 'NOTIFY_GUARD_ACTIVATED':
      log(`🛡️ ガードにより女王の交換が拒否されました`);
      break;
    case 'NOTIFY_TIMEOUT':
      if (payload.autoAction === 'ホストの操作が行われませんでした') {
        log(`⏰ ホストの操作が行われませんでした。自動的に進行します。`);
        showToast('ホストの操作が行われませんでした');
      } else {
        log(`⏰ ${getPlayerName(payload.playerId)} がタイムアウトしました`);
      }
      break;
    case 'WAITING_HOST_PROCEED':
      showWaitingHostProceed(payload.targetPhase);
      break;
    case 'NOTIFY_THINK_TIME':
      log(`🤔 ${getPlayerName(payload.playerId)} が長考を使用しました（+2分）`);
      if (payload.playerId === state.playerId) state.thinkTimeUsed = true;
      state.turnTimeRemaining += payload.newTimeoutSeconds;
      break;
    case 'ENDING_REVEAL_PLAYER':
      showEndingRevealPlayer(payload);
      break;
    case 'SNOW_WHITE_KILLED':
      showSnowWhiteKilled(payload);
      break;
    case 'VICTORY_ANNOUNCEMENT':
      showVictoryAnnouncement(payload);
      break;
    case 'GAME_RESULT':
      showGameResult(payload);
      break;
    case 'NOTIFY_REMATCH_STARTING':
      showScreen('waiting');
      pollRoom();
      log('再ゲームの準備中...');
      break;
    case 'ERROR':
      log(`❌ エラー: ${payload.message}`);
      alert(`エラー: ${payload.message}`);
      break;
    default:
      log(`[未処理] ${type}: ${JSON.stringify(payload)}`);
  }
}

// === Initial Info ===
function handleInitialInfo(payload) {
  state.role = payload.role;
  state.faction = payload.faction;
  state.myApple = payload.myApple;
  state.myHand = payload.myHand || [];
  state.initialInfo = payload;

  const roleInfo = document.getElementById('role-info');
  let info = `あなたの役職: <strong>${ROLE_NAMES[payload.role]}</strong>（${FACTION_NAMES[payload.faction]}）`;
  if (payload.snowWhitePlayerId) info += `<br>👑 白雪姫: ${getPlayerName(payload.snowWhitePlayerId)}`;
  roleInfo.innerHTML = info;
  roleInfo.style.cursor = 'help';
  const roleDesc = ROLE_DESCRIPTIONS[payload.role] || '';
  roleInfo.onmouseenter = (e) => showTooltip(e, roleDesc);
  roleInfo.onmouseleave = () => hideTooltip();
  roleInfo.oncontextmenu = (e) => { e.preventDefault(); showTooltip(e, roleDesc); setTimeout(hideTooltip, 3000); };
  let rolePress;
  roleInfo.ontouchstart = (e) => { rolePress = setTimeout(() => showTooltip(e, roleDesc), 500); };
  roleInfo.ontouchend = () => { clearTimeout(rolePress); setTimeout(hideTooltip, 3000); };

  renderMyApple();
  renderMyHand();
}

// === Game State Sync ===
function handleGameStateSync(payload) {
  state.gameState = payload;
  if (payload.myHand !== undefined && payload.myHand !== null) {
    state.myHand = payload.myHand;
    renderMyHand();
  }
  if (payload.players) renderPlayers(payload.players);
  if (payload.apples) updateAppleDisplay(payload.apples);
  if (payload.deckRemainingCount !== undefined) {
    document.getElementById('deck-info').textContent = `山札: ${payload.deckRemainingCount}枚`;
  }
  if (payload.discardPile) renderDiscardPile(payload.discardPile);
  if (payload.phase) updatePhaseLabel(payload.phase);

  // エンディング/結果フェーズ中はアクションUIを上書きしない（ただし初回遷移時のみ更新）
  const phase = payload.phase;
  const isEndingPhase = phase === 'ENDING_QUEEN' || phase === 'ENDING_REVEAL' || phase === 'FINISHED';

  if (!isEndingPhase) {
    // Update actions if it's my turn
    if (payload.currentTurnPlayerId === state.playerId) {
      renderTurnActions();
    } else if (state.myHand.length >= 2) {
      // カードを引いた直後でSYNCが来た場合（currentTurnPlayerIdがまだ自分の場合も含む）
      renderTurnActions();
    } else {
      const actionsEl = document.getElementById('game-actions');
      const turnPlayerName = getPlayerName(payload.currentTurnPlayerId);
      actionsEl.innerHTML = `<div style="text-align:center;color:#aaa;padding:12px;">🕐 ${turnPlayerName} のターンです。お待ちください…</div>`;
    }
  } else if (phase === 'ENDING_QUEEN') {
    // 女王でないプレイヤーに待機メッセージを表示（女王交換UI表示済みの場合は上書きしない）
    const actionsEl = document.getElementById('game-actions');
    if (!actionsEl.innerHTML.includes('女王の特権')) {
      actionsEl.innerHTML = `<div style="text-align:center;color:#aaa;padding:16px;">👑 女王の特権を実行中です…お待ちください</div>`;
    }
  }
}

// === Turn Changed ===
function handleTurnChanged(payload) {
  if (state.gameState) {
    state.gameState.currentTurnPlayerId = payload.currentTurnPlayerId;
    if (state.gameState.players) renderPlayers(state.gameState.players);
  }
  const name = getPlayerName(payload.currentTurnPlayerId);
  document.getElementById('turn-label').textContent =
    payload.currentTurnPlayerId === state.playerId ? '🎯 あなたのターン' : `${name} のターン`;

  // タイマー開始
  startTurnTimer(payload.timeoutSeconds || 180);

  // 手札表示を更新（ゲーム開始直後の表示確保）
  renderMyHand();

  const phase = state.gameState?.phase;
  const isEndingPhase = phase === 'ENDING_QUEEN' || phase === 'ENDING_REVEAL' || phase === 'FINISHED';
  if (!isEndingPhase) {
    if (payload.currentTurnPlayerId === state.playerId) {
      renderTurnActions();
    } else {
      const actionsEl = document.getElementById('game-actions');
      const turnPlayerName = getPlayerName(payload.currentTurnPlayerId);
      actionsEl.innerHTML = `<div style="text-align:center;color:#aaa;padding:12px;">🕐 ${turnPlayerName} のターンです。お待ちください…</div>`;
    }
  }
}

// === Turn Timer ===
function startTurnTimer(seconds) {
  stopTurnTimer();
  state.turnTimeRemaining = seconds;
  renderTimer();
  state.turnTimer = setInterval(() => {
    state.turnTimeRemaining--;
    renderTimer();
    if (state.turnTimeRemaining <= 0) stopTurnTimer();
  }, 1000);
}

function stopTurnTimer() {
  if (state.turnTimer) {
    clearInterval(state.turnTimer);
    state.turnTimer = null;
  }
}

function renderTimer() {
  const el = document.getElementById('timer-label');
  const sec = state.turnTimeRemaining;
  const min = Math.floor(sec / 60);
  const s = sec % 60;
  el.textContent = `⏱ ${min}:${s.toString().padStart(2, '0')}`;

  // 残り1分で赤色警告
  if (sec <= 60) {
    el.style.color = '#dc2626';
    el.style.fontWeight = 'bold';
  } else {
    el.style.color = '';
    el.style.fontWeight = '';
  }

  // 残り15秒で長考ボタン表示（自分の手番のみ、未使用の場合）
  const thinkBtn = document.getElementById('btn-think-time');
  if (thinkBtn) thinkBtn.remove();
  if (sec <= 15 && sec > 0 && !state.thinkTimeUsed &&
      state.gameState && state.gameState.currentTurnPlayerId === state.playerId) {
    const btn = document.createElement('button');
    btn.id = 'btn-think-time';
    btn.textContent = '🤔 長考（+2分）';
    btn.style.cssText = 'position:fixed;bottom:20px;right:20px;z-index:9999;background:#f59e0b;color:#000;font-weight:bold;padding:10px 20px;border-radius:8px;border:none;cursor:pointer;animation:pulse 0.8s infinite;';
    btn.onclick = () => {
      sendEvent('ACTION_THINK_TIME');
      btn.remove();
    };
    document.body.appendChild(btn);
  }
}

// === Phase Changed ===
function handlePhaseChanged(payload) {
  if (state.gameState) state.gameState.phase = payload.newPhase;
  updatePhaseLabel(payload.newPhase);
  if (payload.newPhase === 'LAST_TURN') {
    log('⚠️ === 最後の手番フェイズに突入しました！ ===');
    const label = document.getElementById('phase-label');
    label.className = 'last-turn';
    // 大きな通知オーバーレイを表示
    showPhaseOverlay('⚠️ 最後の手番フェイズ突入！');
  } else if (payload.newPhase === 'ENDING_QUEEN' || payload.newPhase === 'ENDING_REVEAL') {
    log(`--- ${payload.newPhase === 'ENDING_QUEEN' ? 'エンディング: 女王の特権' : 'エンディング: リンゴ公開'} ---`);
    showPhaseOverlay(payload.newPhase === 'ENDING_QUEEN' ? '👑 エンディング: 女王の特権' : '🍎 エンディング: リンゴ公開');
  }
}

function showPhaseOverlay(message) {
  const overlay = document.createElement('div');
  overlay.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.7);display:flex;align-items:center;justify-content:center;z-index:9999;';
  const box = document.createElement('div');
  box.style.cssText = 'background:#2a2a3e;border:3px solid #dc2626;border-radius:12px;padding:32px 48px;text-align:center;font-size:1.5rem;font-weight:bold;color:#fff;animation:pulse 0.8s 2;';
  box.textContent = message;
  overlay.appendChild(box);
  document.body.appendChild(overlay);
  setTimeout(() => overlay.remove(), 2500);
}

function updatePhaseLabel(phase) {
  const labels = {
    'STORY': 'ストーリーフェイズ', 'LAST_TURN': '最後の手番フェイズ',
    'ENDING_QUEEN': 'エンディング（女王特権）', 'ENDING_REVEAL': 'エンディング（リンゴ公開）'
  };
  const el = document.getElementById('phase-label');
  el.textContent = labels[phase] || phase;
  el.className = phase === 'LAST_TURN' ? 'last-turn' : '';
}

// === Render Players ===
function renderPlayers(players) {
  const container = document.getElementById('game-players');
  container.innerHTML = players.map(p => {
    let cls = 'player-chip';
    if (state.gameState && state.gameState.currentTurnPlayerId === p.playerId) cls += ' current';
    if (p.isAlive === false) cls += ' dead';
    if (p.isProtected) cls += ' protected';
    if (p.isConnected === false) cls += ' disconnected';

    let prefs = '';
    if (p.applePreferenceAnswer !== null && p.applePreferenceAnswer !== undefined)
      prefs += p.applePreferenceAnswer ? '🍎👍' : '🍎👎';
    if (p.mushroomPreferenceAnswer !== null && p.mushroomPreferenceAnswer !== undefined)
      prefs += p.mushroomPreferenceAnswer ? '🍄👍' : '🍄👎';

    let appleIcon = '🔮'; // unknown
    if (state.gameState && state.gameState.apples) {
      const apple = state.gameState.apples.find(a => a.currentHolderPlayerId === p.playerId);
      if (apple && apple.isPoisoned !== null && apple.isPoisoned !== undefined) appleIcon = apple.isPoisoned ? '🍎' : '🍏';
    }

    let roleLabel = '';
    if (p.isRoleRevealed && p.role) roleLabel = `<div style="font-size:0.7rem;color:#f59e0b;">${ROLE_NAMES[p.role] || p.role}</div>`;

    return `<div class="${cls}">
      <div>${p.userName}${p.skipNextTurn ? ' 🔗' : ''}</div>
      ${roleLabel}
      <div class="apple-status">${appleIcon}</div>
      <div class="pref-icons">${prefs}</div>
    </div>`;
  }).join('');
}

// === Render Apple ===
function renderMyApple() {
  const el = document.getElementById('my-apple');
  if (state.myApple && state.myApple.isPoisoned !== null && state.myApple.isPoisoned !== undefined) {
    el.textContent = state.myApple.isPoisoned ? '🍎 あなたのリンゴ: 毒リンゴ' : '🍏 あなたのリンゴ: 安全';
  } else {
    el.textContent = '🔮 あなたのリンゴ: 不明';
  }
}

function updateAppleDisplay(apples) {
  const myApple = apples.find(a => a.currentHolderPlayerId === state.playerId);
  if (myApple) {
    if (myApple.isPubliclyRevealed || (myApple.isPoisoned !== null && myApple.isPoisoned !== undefined)) {
      state.myApple = myApple;
    } else {
      state.myApple = null;
    }
    renderMyApple();
  }
}

// === Render Hand ===
function renderMyHand() {
  const el = document.getElementById('my-hand');
  if (state.myHand.length === 0) {
    el.textContent = '手札: なし';
    return;
  }
  el.innerHTML = '手札: ';
  state.myHand.forEach(c => {
    const span = document.createElement('span');
    span.innerHTML = `<strong>${CARD_NAMES[c.cardType] || c.cardType}</strong> `;
    span.style.cursor = 'help';
    const desc = CARD_DESCRIPTIONS[c.cardType] || '';
    span.onmouseenter = (e) => showTooltip(e, desc);
    span.onmouseleave = () => hideTooltip();
    span.oncontextmenu = (e) => { e.preventDefault(); showTooltip(e, desc); setTimeout(hideTooltip, 3000); };
    let pressTimer;
    span.ontouchstart = (e) => { pressTimer = setTimeout(() => showTooltip(e, desc), 500); };
    span.ontouchend = () => { clearTimeout(pressTimer); setTimeout(hideTooltip, 3000); };
    el.appendChild(span);
  });
}

// === Render Discard Pile ===
function renderDiscardPile(pile) {
  const el = document.getElementById('discard-pile');
  if (!pile || pile.length === 0) {
    el.textContent = '捨て山は空です';
    return;
  }
  el.innerHTML = pile.map((c, i) => `${i + 1}. ${CARD_NAMES[c.cardType] || c.cardType}`).join('<br>');
}

// === Turn Actions ===
function renderTurnActions() {
  const container = document.getElementById('game-actions');
  container.innerHTML = '';
  const gs = state.gameState;
  if (!gs) return;

  // If hand has 2 cards (drew a card), show use/discard options
  if (state.myHand.length >= 2) {
    renderCardChoiceUI();
    return;
  }

  const phase = gs.phase;
  const canDraw = (phase === 'STORY' || phase === 'LAST_TURN') && gs.deckRemainingCount > 1;

  // ① 山札を引く
  const btnDraw = createBtn('① 山札を引く', () => sendEvent('ACTION_DRAW_CARD'));
  btnDraw.disabled = !canDraw;
  container.appendChild(btnDraw);

  // ② 手札の交換
  container.appendChild(createBtn('② 手札の交換', () => showTargetSelect('ACTION_EXCHANGE_HAND')));

  // ③ リンゴの交換
  container.appendChild(createBtn('③ リンゴの交換', () => showTargetSelect('ACTION_EXCHANGE_APPLE')));

  // ④ 自分のリンゴの確認
  container.appendChild(createBtn('④ リンゴ確認', () => showAppleCheckConfirm()));

  // ⑤ 能力発動
  const canAbility = (state.role === 'GRAY' || state.role === 'LIGHT');
  const btnAbility = createBtn('⑤ 能力発動', () => showAbilityUI());
  btnAbility.disabled = !canAbility;
  container.appendChild(btnAbility);

  // ⑥ 手札を使う/捨てる（最後の手番フェイズのみ活性）
  if (state.myHand.length >= 1) {
    const btnHandUse = createBtn('⑥ 手札を使う/捨てる', () => renderCardChoiceUI());
    btnHandUse.disabled = (phase !== 'LAST_TURN');
    container.appendChild(btnHandUse);
  }
}

// === リンゴ確認の確認ダイアログ ===
function showAppleCheckConfirm() {
  const container = document.getElementById('game-actions');
  container.innerHTML = '<div style="width:100%;text-align:center;font-size:0.9rem;font-weight:bold;margin-bottom:12px;">本当にリンゴを確認しますか？</div>';
  container.appendChild(createBtn('確認する', () => sendEvent('ACTION_CHECK_OWN_APPLE')));
  const cancelBtn = createBtn('キャンセル', () => renderTurnActions());
  cancelBtn.classList.add('btn-secondary');
  container.appendChild(cancelBtn);
}

// 最後の手番フェイズでの「戻る」用：カード選択UIを経由せず直接5つのアクションを表示
function renderTurnActions_main() {
  const container = document.getElementById('game-actions');
  container.innerHTML = '';
  const gs = state.gameState;
  if (!gs) return;

  const phase = gs.phase;
  const canDraw = (phase === 'STORY' || phase === 'LAST_TURN') && gs.deckRemainingCount > 1;

  const btnDraw = createBtn('① 山札を引く', () => sendEvent('ACTION_DRAW_CARD'));
  btnDraw.disabled = !canDraw;
  container.appendChild(btnDraw);
  container.appendChild(createBtn('② 手札の交換', () => showTargetSelect('ACTION_EXCHANGE_HAND')));
  container.appendChild(createBtn('③ リンゴの交換', () => showTargetSelect('ACTION_EXCHANGE_APPLE')));
  container.appendChild(createBtn('④ リンゴ確認', () => showAppleCheckConfirm()));

  const canAbility = (state.role === 'GRAY' || state.role === 'LIGHT');
  const btnAbility = createBtn('⑤ 能力発動', () => showAbilityUI());
  btnAbility.disabled = !canAbility;
  container.appendChild(btnAbility);

  // 手札を使う/捨てるオプション（最後の手番フェイズのみ活性）
  if (state.myHand.length >= 1) {
    const btnHandUse = createBtn('⑥ 手札を使う/捨てる', () => renderCardChoiceUI());
    btnHandUse.disabled = (phase !== 'LAST_TURN');
    container.appendChild(btnHandUse);
  }
}

function renderCardChoiceUI() {
  const container = document.getElementById('game-actions');
  const msg = state.myHand.length >= 2
    ? '手札が2枚あります。1枚を使用または廃棄してください:'
    : '手札のカードを使用または廃棄してください:';
  container.innerHTML = `<div style="width:100%;text-align:center;font-size:0.85rem;margin-bottom:8px;">${msg}</div>`;

  state.myHand.forEach(card => {
    const isCursedRing = card.cardType === 'CURSED_RING';
    const isPassive = card.cardType === 'GUARD' || card.cardType === 'KNIGHT';
    const isPoisonCombInStory = card.cardType === 'POISON_COMB' && state.gameState && state.gameState.phase === 'STORY';
    const cannotUse = isCursedRing || isPassive || isPoisonCombInStory;

    const row = document.createElement('div');
    row.style.cssText = 'display:flex;gap:8px;width:100%;margin-bottom:8px;align-items:center;';

    const label = document.createElement('span');
    label.style.cssText = 'flex:1;font-size:0.85rem;font-weight:bold;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;';
    label.textContent = CARD_NAMES[card.cardType] || card.cardType;
    row.appendChild(label);

    const btnUse = createBtn('使う', () => showCardUseUI(card));
    btnUse.disabled = cannotUse;
    btnUse.style.cssText = 'flex:0 0 auto;';
    row.appendChild(btnUse);

    const btnDiscard = createBtn('捨てる', () => sendEvent('ACTION_DISCARD_CARD', { cardId: card.cardId }));
    btnDiscard.disabled = isCursedRing;
    btnDiscard.classList.add('btn-secondary');
    btnDiscard.style.cssText = 'flex:0 0 auto;';
    row.appendChild(btnDiscard);

    container.appendChild(row);
  });

  // 最後の手番フェイズで手札1枚の場合は他の行動も選べるのでキャンセルを表示
  if (state.myHand.length < 2) {
    const cancelBtn = createBtn('← 戻る', () => renderTurnActions_main());
    cancelBtn.classList.add('btn-secondary');
    container.appendChild(cancelBtn);
  }
}

// === Card Use UI ===
function showCardUseUI(card) {
  const container = document.getElementById('game-actions');
  container.innerHTML = `<div style="width:100%;text-align:center;font-size:0.9rem;margin-bottom:8px;">「${CARD_NAMES[card.cardType]}」を使用</div>`;

  const gs = state.gameState;
  const alivePlayers = gs.players.filter(p => p.isAlive && p.playerId !== state.playerId);

  switch (card.cardType) {
    case 'APPLE_QUESTION':
    case 'MUSHROOM_QUESTION':
      // 既に同じ質問に答えたプレイヤーは対象外
      const qTargets = gs.players.filter(p => {
        if (!p.isAlive) return false;
        if (card.cardType === 'APPLE_QUESTION' && p.applePreferenceAnswer !== null && p.applePreferenceAnswer !== undefined) return false;
        if (card.cardType === 'MUSHROOM_QUESTION' && p.mushroomPreferenceAnswer !== null && p.mushroomPreferenceAnswer !== undefined) return false;
        return true;
      });
      qTargets.forEach(p => {
        container.appendChild(createBtn(p.userName, () =>
          sendEvent('ACTION_USE_CARD', { cardId: card.cardId, cardType: card.cardType, params: { targetPlayerId: p.playerId } })
        ));
      });
      break;

    case 'KNIFE':
    case 'POISON_COMB':
      alivePlayers.forEach(p => {
        container.appendChild(createBtn(p.userName, () =>
          sendEvent('ACTION_USE_CARD', { cardId: card.cardId, cardType: card.cardType, params: { targetPlayerId: p.playerId } })
        ));
      });
      break;

    case 'ROPE':
      // ロープは自分含む全生存プレイヤーが対象
      gs.players.filter(p => p.isAlive).forEach(p => {
        container.appendChild(createBtn(p.userName, () =>
          sendEvent('ACTION_USE_CARD', { cardId: card.cardId, cardType: card.cardType, params: { targetPlayerId: p.playerId } })
        ));
      });
      break;

    case 'ITADAKIMASU':
      const maxDiscard = Math.min(3, Math.max(0, gs.deckRemainingCount - 1));
      if (maxDiscard === 0) {
        container.innerHTML += '<div style="width:100%;text-align:center;color:#aaa;font-size:0.85rem;">山札の最後の1枚は捨てられません</div>';
      }
      for (let i = 1; i <= maxDiscard; i++) {
        container.appendChild(createBtn(`${i}枚捨てる`, () =>
          sendEvent('ACTION_USE_CARD', { cardId: card.cardId, cardType: card.cardType, params: { count: String(i) } })
        ));
      }
      break;

    case 'ROULETTE_1':
    case 'ROULETTE_2':
    case 'ROULETTE_3':
      container.appendChild(createBtn('⟳ 時計回り', () =>
        sendEvent('ACTION_USE_CARD', { cardId: card.cardId, cardType: card.cardType, params: { direction: 'CLOCKWISE' } })
      ));
      container.appendChild(createBtn('⟲ 反時計回り', () =>
        sendEvent('ACTION_USE_CARD', { cardId: card.cardId, cardType: card.cardType, params: { direction: 'COUNTER_CLOCKWISE' } })
      ));
      break;

    case 'PRESENT_EXCHANGE':
      container.innerHTML += '<div style="width:100%;font-size:0.8rem;">2名を選んでください:</div>';
      let selected = [];
      alivePlayers.forEach(p => {
        const btn = createBtn(p.userName, () => {
          if (selected.includes(p.playerId)) {
            selected = selected.filter(id => id !== p.playerId);
            btn.style.background = '#444';
          } else {
            selected.push(p.playerId);
            btn.style.background = '#10b981';
          }
          if (selected.length === 2) {
            sendEvent('ACTION_USE_CARD', { cardId: card.cardId, cardType: card.cardType, params: { targetPlayerIdA: selected[0], targetPlayerIdB: selected[1] } });
          }
        });
        btn.classList.add('btn-secondary');
        container.appendChild(btn);
      });
      break;

    default:
      sendEvent('ACTION_USE_CARD', { cardId: card.cardId, cardType: card.cardType, params: {} });
  }

  container.appendChild(createBtn('キャンセル', () => renderCardChoiceUI()));
}

// === Target Selection ===
function showTargetSelect(eventType) {
  const container = document.getElementById('game-actions');
  container.innerHTML = '<div style="width:100%;text-align:center;font-size:0.85rem;">対象プレイヤーを選択:</div>';
  const gs = state.gameState;
  const targets = gs.players.filter(p => p.isAlive && p.playerId !== state.playerId && !p.isProtected);

  targets.forEach(p => {
    container.appendChild(createBtn(p.userName, () =>
      sendEvent(eventType, { targetPlayerId: p.playerId })
    ));
  });
  container.appendChild(createBtn('キャンセル', () => renderTurnActions()));
}

// === Ability UI ===
function showAbilityUI() {
  const container = document.getElementById('game-actions');
  container.innerHTML = '';

  if (state.role === 'GRAY') {
    container.appendChild(createBtn('グレイの能力を発動（保護）', () => sendEvent('ACTION_USE_ABILITY', { params: {} })));
  } else if (state.role === 'LIGHT') {
    container.innerHTML = '<div style="width:100%;font-size:0.85rem;">リンゴを交換させる2名を選択:</div>';
    const gs = state.gameState;
    const targets = gs.players.filter(p => p.isAlive && !p.isProtected);
    let selected = [];
    targets.forEach(p => {
      const btn = createBtn(p.userName, () => {
        if (selected.includes(p.playerId)) {
          selected = selected.filter(id => id !== p.playerId);
          btn.style.background = '#444';
        } else {
          selected.push(p.playerId);
          btn.style.background = '#10b981';
        }
        if (selected.length === 2) {
          sendEvent('ACTION_USE_ABILITY', { params: { targetPlayerIdA: selected[0], targetPlayerIdB: selected[1] } });
        }
      });
      btn.classList.add('btn-secondary');
      container.appendChild(btn);
    });
  }
  container.appendChild(createBtn('キャンセル', () => renderTurnActions()));
}

// === Preference UI ===
function showPreferenceUI(payload) {
  const container = document.getElementById('game-actions');
  const qLabel = payload.questionType === 'APPLE' ? 'リンゴは好きですか？' : 'きのこは好きですか？';
  container.innerHTML = `<div style="width:100%;text-align:center;font-weight:bold;">${qLabel}</div>`;

  // Determine if player can lie (Navy only)
  const isNavy = state.role === 'NAVY';
  const correctAnswer = getCorrectPreference(payload.questionType);

  const btnYes = createBtn('はい（好き）', () => sendEvent('RESPONSE_PREFERENCE', { answer: true }));
  const btnNo = createBtn('いいえ（嫌い）', () => sendEvent('RESPONSE_PREFERENCE', { answer: false }));

  if (!isNavy) {
    if (correctAnswer === true) btnNo.disabled = true;
    else btnYes.disabled = true;
  }

  container.appendChild(btnYes);
  container.appendChild(btnNo);
}

function getCorrectPreference(questionType) {
  const prefs = {
    SNOW_WHITE: { APPLE: true, MUSHROOM: false },
    QUEEN: { APPLE: true, MUSHROOM: false },
    GREEN: { APPLE: false, MUSHROOM: true },
    BLACK: { APPLE: false, MUSHROOM: true },
    BROWN: { APPLE: true, MUSHROOM: true },
    GRAY: { APPLE: false, MUSHROOM: true },
    NAVY: { APPLE: null, MUSHROOM: null },
    ROSE: { APPLE: false, MUSHROOM: false },
    LIGHT: { APPLE: true, MUSHROOM: false }
  };
  return prefs[state.role]?.[questionType] ?? null;
}

// === Queen Exchange UI ===
function showQueenExchangeUI(payload) {
  const container = document.getElementById('game-actions');
  container.innerHTML = '<div style="width:100%;text-align:center;font-weight:bold;">女王の特権: リンゴ交換対象を選択</div>';

  payload.availableTargetPlayerIds.forEach(id => {
    container.appendChild(createBtn(getPlayerName(id), () =>
      sendEvent('RESPONSE_QUEEN_EXCHANGE', { targetPlayerId: id })
    ));
  });
}

// === エンディング：一人ずつ役職公開 ===
function showEndingRevealPlayer(payload) {
  const container = document.getElementById('game-actions');
  const causeLabel = payload.isPoisoned ? '☠️ 毒リンゴ → 死亡' : '🍏 安全 → 生存';
  const aliveLabel = payload.isAlive ? '✅ 生存' : '💀 死亡';
  const factionName = FACTION_NAMES[payload.faction] || payload.faction;
  const roleName = ROLE_NAMES[payload.role] || payload.role;

  container.innerHTML = `
    <div style="text-align:center;padding:16px;">
      <div style="font-size:0.8rem;color:#888;margin-bottom:4px;">公開 ${payload.revealIndex + 1} / ${payload.totalPlayers}</div>
      <div style="font-size:1.3rem;font-weight:bold;margin-bottom:8px;">${payload.userName}</div>
      <div style="font-size:1rem;margin-bottom:4px;">役職: <strong>${roleName}</strong>（${factionName}）</div>
      <div style="font-size:1.1rem;margin-bottom:4px;">${payload.isPoisoned ? '🍎 毒リンゴ' : '🍏 安全リンゴ'}</div>
      <div style="font-size:1.2rem;font-weight:bold;color:${payload.isAlive ? '#10b981' : '#ef4444'};">${aliveLabel}</div>
    </div>
  `;
  log(`📋 ${payload.userName}: ${roleName}（${factionName}）- ${payload.isPoisoned ? '毒リンゴ' : '安全'} → ${payload.isAlive ? '生存' : '死亡'}`);
}

// === ホスト操作待ち ===
function showWaitingHostProceed(targetPhase) {
  const container = document.getElementById('game-actions');
  const label = targetPhase === 'ENDING_REVEAL' ? 'エンディングリビールに進む' : '結果画面に進む';
  const eventType = targetPhase === 'ENDING_REVEAL' ? 'PROCEED_TO_REVEAL' : 'PROCEED_TO_RESULT';

  if (state.isHost) {
    container.innerHTML = `<div style="width:100%;text-align:center;padding:16px;">
      <div style="margin-bottom:12px;font-weight:bold;">準備ができたら進めてください</div>
      <button id="host-proceed-btn" style="padding:12px 24px;font-size:1.1rem;background:#10b981;color:#fff;border:none;border-radius:8px;cursor:pointer;">${label}</button>
    </div>`;
    document.getElementById('host-proceed-btn').onclick = () => {
      sendEvent(eventType, {});
      container.innerHTML = '<div style="text-align:center;padding:16px;">進行中...</div>';
    };
  } else {
    container.innerHTML = `<div style="width:100%;text-align:center;padding:16px;color:#aaa;">ホストの操作を待っています…</div>`;
  }
  log(`⏳ ${label}（ホスト操作待ち）`);
}

// === 白雪姫死亡の特殊演出 ===
function showSnowWhiteKilled(payload) {
  const container = document.getElementById('game-actions');
  const causeMap = {
    'CURSED_RING': '💍 呪いの指輪により',
    'POISON_COMB': '🪮 毒の櫛により',
    'DISCONNECTED': '📡 接続切断により',
    'POISON_APPLE': '🍎 毒リンゴにより'
  };
  const causeText = causeMap[payload.cause] || payload.cause;
  const snowWhiteName = getPlayerName(payload.snowWhitePlayerId);

  container.innerHTML = `
    <div style="text-align:center;padding:24px;background:linear-gradient(135deg,#1a0000,#330000);border-radius:12px;border:2px solid #ef4444;">
      <div style="font-size:2rem;margin-bottom:12px;">👑💀</div>
      <div style="font-size:1.3rem;font-weight:bold;color:#ef4444;margin-bottom:8px;">白雪姫が倒れました…</div>
      <div style="font-size:1rem;color:#fca5a5;margin-bottom:4px;">${causeText}</div>
      <div style="font-size:1rem;color:#fca5a5;">${snowWhiteName} は白雪姫でした</div>
      <div style="font-size:1.1rem;font-weight:bold;color:#f59e0b;margin-top:16px;">👑 女王陣営の勝利！</div>
    </div>
  `;
  log(`👑💀 白雪姫（${snowWhiteName}）が${causeText}死亡しました。女王陣営の勝利！`);
}

// === 勝利演出 ===
function showVictoryAnnouncement(payload) {
  const container = document.getElementById('game-actions');
  let html = '';

  if (payload.winFaction === 'SNOW_WHITE_FACTION') {
    html = `
      <div style="text-align:center;padding:24px;background:linear-gradient(135deg,#001a0a,#003318);border-radius:12px;border:2px solid #10b981;">
        <div style="font-size:2.5rem;margin-bottom:12px;">🍏👑✨</div>
        <div style="font-size:1.5rem;font-weight:bold;color:#10b981;margin-bottom:8px;">白雪姫は生き残った！</div>
        <div style="font-size:1.2rem;color:#6ee7b7;margin-bottom:4px;">毒リンゴの呪いを乗り越えて…</div>
        <div style="font-size:1.3rem;font-weight:bold;color:#34d399;margin-top:16px;">🎉 白雪姫陣営の勝利！</div>
      </div>
    `;
    log(`🎉 白雪姫は生き残った！白雪姫陣営の勝利！`);
  } else if (payload.winFaction === 'QUEEN_FACTION') {
    html = `
      <div style="text-align:center;padding:24px;background:linear-gradient(135deg,#1a0000,#330000);border-radius:12px;border:2px solid #ef4444;">
        <div style="font-size:2.5rem;margin-bottom:12px;">👑☠️🍎</div>
        <div style="font-size:1.5rem;font-weight:bold;color:#ef4444;margin-bottom:8px;">白雪姫は毒に倒れた…</div>
        <div style="font-size:1.2rem;color:#fca5a5;margin-bottom:4px;">女王の策略は成功した</div>
        <div style="font-size:1.3rem;font-weight:bold;color:#f59e0b;margin-top:16px;">👑 女王陣営の勝利！</div>
      </div>
    `;
    log(`👑 白雪姫は毒に倒れた… 女王陣営の勝利！`);
  } else if (payload.winFaction === 'THIRD_FACTION') {
    html = `
      <div style="text-align:center;padding:24px;background:linear-gradient(135deg,#1a001a,#330033);border-radius:12px;border:2px solid #c084fc;">
        <div style="font-size:2.5rem;margin-bottom:12px;">🌹💀✨</div>
        <div style="font-size:1.5rem;font-weight:bold;color:#c084fc;margin-bottom:8px;">ロゼの願いが叶った…</div>
        <div style="font-size:1.2rem;color:#e9d5ff;margin-bottom:4px;">白雪姫は生き残り、ロゼは毒と共に眠る</div>
        <div style="font-size:1.3rem;font-weight:bold;color:#a855f7;margin-top:16px;">🌹 第三陣営の勝利！</div>
      </div>
    `;
    log(`🌹 ロゼの願いが叶った… 第三陣営の勝利！`);
  }

  container.innerHTML = html;
}

// === Game Result ===
function showGameResult(payload) {
  showScreen('result');
  const title = document.getElementById('result-title');
  if (payload.reason === 'SNOW_WHITE_DISCONNECTED') {
    title.textContent = '白雪姫が切断したためゲームが終了しました';
  } else if (payload.reason === 'SNOW_WHITE_KILLED') {
    title.textContent = '白雪姫が倒れました… 女王陣営の勝利！';
  } else {
    title.textContent = `${FACTION_NAMES[payload.winFaction] || payload.winFaction} の勝利！`;
  }

  const details = document.getElementById('result-details');
  details.innerHTML = payload.players.map(p => {
    const roleLabel = ROLE_NAMES[p.role] || p.role;
    const appleLabel = p.apple?.isPoisoned ? '🍎毒' : '🍏安全';
    const alive = p.isAlive ? '生存' : '☠️死亡';
    const winner = p.isWinner ? '<span class="winner-badge">🏆</span>' : '';
    return `<div class="result-player">
      <span>${p.userName} (${roleLabel}) ${winner}</span>
      <span>${appleLabel} / ${alive}</span>
    </div>`;
  }).join('');

  if (state.isHost) {
    document.getElementById('btn-rematch').style.display = 'block';
  }
}

// === Helpers ===
function getPlayerName(playerId) {
  const players = state.gameState?.players || state.players;
  if (!players) return playerId?.substring(0, 6) || '???';
  const p = players.find(p => p.playerId === playerId);
  return p?.userName || playerId?.substring(0, 6) || '???';
}

function createBtn(text, onClick) {
  const btn = document.createElement('button');
  btn.textContent = text;
  btn.onclick = onClick;
  return btn;
}

function log(msg) {
  const el = document.getElementById('game-log');
  if (el) {
    const div = document.createElement('div');
    div.textContent = `[${new Date().toLocaleTimeString()}] ${msg}`;
    el.appendChild(div);
    el.scrollTop = el.scrollHeight;
  }
  console.log(msg);
}

// === Room polling ===
async function pollRoom() {
  try {
    const data = await api('GET', '/' + state.roomCode);
    const totalSeats = data.settings?.roles?.length || data.players.length;
    document.getElementById('player-count').textContent = data.players.length;
    document.getElementById('max-players').textContent = totalSeats;

    // Store room players for seat rendering
    state.roomPlayers = data.players;
    state.totalSeats = totalSeats;
    if (!state.selectedRoles.length && data.settings?.roles) state.selectedRoles = data.settings.roles;

    renderSeatCircle();

    // Enable start button when player count matches role count
    if (state.isHost) {
      const roleCount = state.selectedRoles.length;
      const btn = document.getElementById('btn-start-game');
      btn.disabled = roleCount === 0 || data.players.length !== roleCount;
    }
  } catch(e) { console.error(e); }
}

function renderSeatCircle() {
  const container = document.getElementById('seat-circle');
  if (!container) return;
  const players = state.roomPlayers || [];
  const totalSeats = state.totalSeats || players.length || 4;
  const radius = 100;
  const centerX = 130;
  const centerY = 130;

  container.style.cssText = 'position:relative;width:260px;height:260px;margin:16px auto;';
  container.innerHTML = '';

  for (let i = 0; i < totalSeats; i++) {
    // Start from 12 o'clock (-90deg), clockwise
    const angle = (-90 + (360 / totalSeats) * i) * (Math.PI / 180);
    const x = centerX + radius * Math.cos(angle) - 35;
    const y = centerY + radius * Math.sin(angle) - 25;

    const player = players.find(p => p.seatOrder === i);
    const seat = document.createElement('div');
    seat.className = 'seat' + (player ? ' occupied' : '') + (state.dragOverSeat === i ? ' drag-over' : '');
    seat.style.cssText = `position:absolute;left:${x}px;top:${y}px;width:70px;height:50px;border-radius:8px;display:flex;flex-direction:column;align-items:center;justify-content:center;font-size:0.7rem;background:${player ? '#2a4a3e' : '#333'};border:2px solid ${player ? '#10b981' : '#555'};color:#eee;cursor:${state.isHost && player ? 'grab' : 'default'};user-select:none;`;
    seat.dataset.seatIndex = i;

    const orderLabel = document.createElement('div');
    orderLabel.style.cssText = 'font-size:0.6rem;color:#888;';
    orderLabel.textContent = `#${i + 1}`;
    seat.appendChild(orderLabel);

    const nameLabel = document.createElement('div');
    nameLabel.style.cssText = 'font-weight:bold;font-size:0.75rem;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:65px;';
    nameLabel.textContent = player ? player.userName : '空席';
    seat.appendChild(nameLabel);

    if (player && player.isHost) {
      const badge = document.createElement('div');
      badge.style.cssText = 'font-size:0.55rem;color:#f59e0b;';
      badge.textContent = 'ホスト';
      seat.appendChild(badge);
    }

    // Drag-and-drop for host
    if (state.isHost && player) {
      seat.draggable = true;
      seat.ondragstart = (e) => {
        state.dragPlayerId = player.playerId;
        state.dragFromSeat = i;
        e.dataTransfer.effectAllowed = 'move';
        seat.style.opacity = '0.5';
        sendEvent('DRAG_SEAT', { dragPlayerId: player.playerId, overSeatIndex: i });
      };
      seat.ondragend = () => {
        seat.style.opacity = '1';
        state.dragPlayerId = null;
        state.dragOverSeat = null;
        container.querySelectorAll('.seat').forEach(s => s.classList.remove('drag-over'));
      };
    }

    seat.ondragover = (e) => {
      e.preventDefault();
      e.dataTransfer.dropEffect = 'move';
      if (state.isHost && state.dragPlayerId) {
        // Remove highlight from all seats, add to this one
        container.querySelectorAll('.seat').forEach(s => s.classList.remove('drag-over'));
        seat.classList.add('drag-over');
        state.dragOverSeat = i;
      }
    };

    seat.ondragleave = () => {
      seat.classList.remove('drag-over');
    };

    seat.ondrop = (e) => {
      e.preventDefault();
      if (!state.isHost || !state.dragPlayerId) return;
      const targetPlayer = players.find(p => p.seatOrder === i);
      if (targetPlayer && targetPlayer.playerId !== state.dragPlayerId) {
        sendEvent('SWAP_SEATS', { playerIdA: state.dragPlayerId, playerIdB: targetPlayer.playerId });
      } else if (!targetPlayer) {
        // Drop on empty seat - swap with the dragged player's original position
        // This case needs special handling on server; for now just ignore
      }
      state.dragPlayerId = null;
      state.dragOverSeat = null;
    };

    container.appendChild(seat);
  }
}

// === Username validation ===
document.getElementById('input-username').addEventListener('input', (e) => {
  const valid = e.target.value.trim().length > 0;
  document.getElementById('btn-create-room').disabled = !valid;
  document.getElementById('btn-join-room').disabled = !valid;
  document.getElementById('btn-rejoin-room').disabled = !valid;
});

// === Event listeners ===
document.getElementById('btn-create-room').onclick = () => {
  state.username = document.getElementById('input-username').value.trim();
  showScreen('create');
};

document.getElementById('btn-join-room').onclick = () => {
  state.username = document.getElementById('input-username').value.trim();
  showScreen('join');
};

document.getElementById('btn-rejoin-room').onclick = () => {
  state.username = document.getElementById('input-username').value.trim();
  showScreen('rejoin');
};

document.getElementById('btn-reset-cards').onclick = () => {
  const defaults = { APPLE_QUESTION: 5, MUSHROOM_QUESTION: 5, ITADAKIMASU: 3, ROULETTE_1: 1, ROULETTE_2: 1, ROULETTE_3: 1, KNIFE: 1, CURSED_RING: 1, POISON_COMB: 1, KNIGHT: 1, GUARD: 1, ROPE: 1, PRESENT_EXCHANGE: 1 };
  document.querySelectorAll('#card-settings input').forEach(inp => {
    inp.value = defaults[inp.dataset.card] || 1;
  });
};

document.getElementById('btn-do-create').onclick = async () => {
  const poisonCount = parseInt(document.getElementById('input-poison-count').value) || 2;
  const roles = [];
  document.querySelectorAll('#role-checkboxes input:checked').forEach(cb => roles.push(cb.value));
  state.selectedRoles = roles;

  const cardSettings = [];
  document.querySelectorAll('#card-settings input').forEach(inp => {
    cardSettings.push({ cardType: inp.dataset.card, count: parseInt(inp.value) || 0 });
  });

  try {
    const data = await api('POST', '', {
      userName: state.username,
      poisonAppleCount: poisonCount,
      roles: roles,
      cardSettings: cardSettings
    });
    state.roomId = data.roomId;
    state.roomCode = data.roomCode;
    state.playerId = data.playerId;
    state.isHost = true;
    document.getElementById('display-room-code').textContent = data.roomCode;
    document.getElementById('btn-start-game').style.display = 'block';
    document.getElementById('btn-shuffle-seats').style.display = 'block';
    document.getElementById('waiting-msg').style.display = 'none';
    showScreen('waiting');
    connectWs();
    pollRoom();
    state.pollInterval = setInterval(pollRoom, 3000);
  } catch(e) { alert(e.message); }
};

document.getElementById('btn-do-join').onclick = async () => {
  const code = document.getElementById('input-room-code').value.trim();
  if (!code) return alert('コードを入力してください');
  try {
    const data = await api('POST', '/' + code + '/join', { userName: state.username });
    state.roomId = data.roomId;
    state.roomCode = data.roomCode || code;
    state.playerId = data.playerId;
    state.isHost = false;
    document.getElementById('display-room-code').textContent = state.roomCode;
    document.getElementById('btn-start-game').style.display = 'none';
    document.getElementById('waiting-msg').style.display = 'block';
    showScreen('waiting');
    connectWs();
    pollRoom();
    state.pollInterval = setInterval(pollRoom, 3000);
  } catch(e) { alert(e.message); }
};

document.getElementById('btn-do-rejoin').onclick = async () => {
  const code = document.getElementById('input-rejoin-code').value.trim();
  if (!code) return alert('ルームコードを入力してください');
  try {
    const data = await api('POST', '/' + code + '/rejoin', { userName: state.username });
    state.roomId = data.roomId;
    state.roomCode = data.roomCode || code;
    state.playerId = data.playerId;
    state.isHost = false;

    if (data.status === 'IN_GAME') {
      // ゲーム中に復帰 → WebSocket接続してゲーム画面へ
      showScreen('game');
      document.getElementById('room-code-label').textContent = `🏠 ${state.roomCode}`;
      connectWs();
    } else {
      // 待機中に復帰
      document.getElementById('display-room-code').textContent = state.roomCode;
      document.getElementById('btn-start-game').style.display = 'none';
      document.getElementById('waiting-msg').style.display = 'block';
      showScreen('waiting');
      connectWs();
      pollRoom();
      state.pollInterval = setInterval(pollRoom, 3000);
    }
  } catch(e) { alert(e.message); }
};

document.getElementById('btn-start-game').onclick = () => {
  sendEvent('GAME_START', {});
};

document.getElementById('btn-shuffle-seats').onclick = () => {
  sendEvent('SHUFFLE_SEATS', {});
};

document.getElementById('btn-rematch').onclick = () => {
  sendEvent('REMATCH_REQUEST', {});
};

const btnBackToStart = document.getElementById('btn-back-to-start');
if (btnBackToStart) btnBackToStart.onclick = () => { resetAndGoHome(); };
document.getElementById('btn-leave-room').onclick = () => {
  if (state.isHost) {
    if (!confirm('ルームを解散しますか？')) return;
    sendEvent('DISBAND_ROOM');
  } else {
    sendEvent('LEAVE_ROOM');
  }
  resetAndGoHome();
};
document.getElementById('btn-disbanded-home').onclick = () => { resetAndGoHome(); };
document.getElementById('btn-disband-result').onclick = () => { resetAndGoHome(); };

function resetAndGoHome() {
  if (state.ws) state.ws.close();
  if (state.pollInterval) clearInterval(state.pollInterval);
  state = { screen: 'start', username: '', roomId: '', roomCode: '', playerId: '', isHost: false, ws: null, role: null, faction: null, myApple: null, myHand: [], gameState: null, initialInfo: null, selectedRoles: [], pollInterval: null, players: [] };
  showScreen('start');
}

document.querySelectorAll('.btn-back').forEach(btn => {
  btn.onclick = () => showScreen(btn.dataset.back);
});

document.getElementById('btn-show-discard').onclick = () => {
  const el = document.getElementById('discard-pile');
  el.style.display = el.style.display === 'none' ? 'block' : 'none';
};

document.getElementById('btn-show-reference').onclick = () => {
  const el = document.getElementById('role-reference');
  el.style.display = el.style.display === 'none' ? 'block' : 'none';
};

// === Tooltip for role and cards ===
const CARD_DESCRIPTIONS = {
  APPLE_QUESTION: '任意のプレイヤー1名に「リンゴが好きか」を質問する',
  MUSHROOM_QUESTION: '任意のプレイヤー1名に「きのこが好きか」を質問する',
  ITADAKIMASU: '山札の上から1〜3枚を捨て山に捨てる',
  ROULETTE_1: '全員のリンゴを時計/反時計回りに1個ずらす',
  ROULETTE_2: '全員のリンゴを時計/反時計回りに2個ずらす',
  ROULETTE_3: '全員のリンゴを時計/反時計回りに3個ずらす',
  KNIFE: '任意のプレイヤーのリンゴを全員に公開する',
  CURSED_RING: '捨てられない。最終フェイズで手番が来たら即死',
  POISON_COMB: '【最終フェイズ専用】任意のプレイヤーを即死させる',
  KNIGHT: '【受動】白雪姫が持っていると毒の櫛を無効化',
  GUARD: '【受動】女王のリンゴ交換を自動拒否',
  ROPE: '任意のプレイヤーの次の手番をスキップさせる',
  PRESENT_EXCHANGE: '任意の2名の手札を交換させる'
};

const ROLE_DESCRIPTIONS = {
  SNOW_WHITE: '白雪姫陣営。特殊能力なし。生存が陣営の勝利条件。',
  QUEEN: '女王陣営。エンディングで毒リンゴ所持時に交換可能。',
  GREEN: '白雪姫陣営。白雪姫が誰かを知っている。',
  BLACK: '女王陣営。毒リンゴの位置を常に把握している。',
  BROWN: '白雪姫陣営。特殊能力なし。',
  GRAY: '白雪姫陣営。能力発動で次の手番まで交換対象にならない（1回）。',
  NAVY: '女王陣営。質問への回答で嘘がつける。',
  ROSE: '第三陣営。白雪姫と自分の両方が生存で勝利。',
  LIGHT: '白雪姫陣営。役職公開して任意2名のリンゴを交換させる（1回）。'
};

function showTooltip(e, text) {
  hideTooltip();
  const tip = document.createElement('div');
  tip.id = 'tooltip';
  tip.textContent = text;
  tip.style.cssText = 'position:fixed;z-index:10000;background:#222;color:#eee;padding:8px 12px;border-radius:6px;font-size:0.8rem;max-width:250px;pointer-events:none;box-shadow:0 2px 8px rgba(0,0,0,0.5);';
  document.body.appendChild(tip);
  const rect = e.target.getBoundingClientRect();
  tip.style.left = Math.min(rect.left, window.innerWidth - 260) + 'px';
  tip.style.top = (rect.bottom + 6) + 'px';
}

function hideTooltip() {
  const existing = document.getElementById('tooltip');
  if (existing) existing.remove();
}

