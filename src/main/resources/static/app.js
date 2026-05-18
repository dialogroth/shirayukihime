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
  pollInterval: null
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
    case 'GAME_STARTED':
      showScreen('game');
      if (payload.players) renderPlayers(payload.players);
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
      log(`${getPlayerName(payload.playerId)} が「${CARD_NAMES[payload.cardType] || payload.cardType}」を使用しました`);
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
      state.myApple = null;
      renderMyApple();
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
      log(`⏰ ${getPlayerName(payload.playerId)} がタイムアウトしました`);
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
  if (payload.poisonAppleHolderIds) info += `<br>☠️ 毒リンゴ所持: ${payload.poisonAppleHolderIds.map(id => getPlayerName(id)).join(', ')}`;
  roleInfo.innerHTML = info;

  renderMyApple();
  renderMyHand();
}

// === Game State Sync ===
function handleGameStateSync(payload) {
  state.gameState = payload;
  if (payload.myHand) {
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

  // Update actions if it's my turn
  if (payload.currentTurnPlayerId === state.playerId) {
    renderTurnActions();
  } else {
    const actionsEl = document.getElementById('game-actions');
    const turnPlayerName = getPlayerName(payload.currentTurnPlayerId);
    actionsEl.innerHTML = `<div style="text-align:center;color:#aaa;padding:12px;">🕐 ${turnPlayerName} のターンです。お待ちください…</div>`;
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

  if (payload.currentTurnPlayerId === state.playerId) {
    renderTurnActions();
  } else {
    const actionsEl = document.getElementById('game-actions');
    const turnPlayerName = getPlayerName(payload.currentTurnPlayerId);
    actionsEl.innerHTML = `<div style="text-align:center;color:#aaa;padding:12px;">🕐 ${turnPlayerName} のターンです。お待ちください…</div>`;
  }
}

// === Phase Changed ===
function handlePhaseChanged(payload) {
  updatePhaseLabel(payload.newPhase);
  if (payload.newPhase === 'ENDING_QUEEN' || payload.newPhase === 'ENDING_REVEAL') {
    // Stay on game screen, show ending info in log
    log(`--- ${payload.newPhase === 'ENDING_QUEEN' ? 'エンディング: 女王の特権' : 'エンディング: リンゴ公開'} ---`);
  }
}

function updatePhaseLabel(phase) {
  const labels = {
    'STORY': 'ストーリーフェイズ', 'LAST_TURN': '最後の手番フェイズ',
    'ENDING_QUEEN': 'エンディング（女王特権）', 'ENDING_REVEAL': 'エンディング（リンゴ公開）'
  };
  document.getElementById('phase-label').textContent = labels[phase] || phase;
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
      if (apple && apple.isPubliclyRevealed) appleIcon = apple.isPoisoned ? '🍎' : '🍏';
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
  if (state.myApple) {
    el.textContent = state.myApple.isPoisoned ? '🍎 あなたのリンゴ: 毒リンゴ' : '🍏 あなたのリンゴ: 安全';
  } else {
    el.textContent = '🔮 あなたのリンゴ: 不明';
  }
}

function updateAppleDisplay(apples) {
  // Update myApple if publicly revealed
  const myApple = apples.find(a => a.currentHolderPlayerId === state.playerId);
  if (myApple && (myApple.isPubliclyRevealed || myApple.isPoisoned !== null)) {
    state.myApple = myApple;
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
  el.innerHTML = '手札: ' + state.myHand.map(c =>
    `<strong>${CARD_NAMES[c.cardType] || c.cardType}</strong>`
  ).join(', ');
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
  const canDraw = phase === 'STORY' && gs.deckRemainingCount > 0;

  // ① 山札を引く
  const btnDraw = createBtn('① 山札を引く', () => sendEvent('ACTION_DRAW_CARD'));
  btnDraw.disabled = !canDraw;
  container.appendChild(btnDraw);

  // ② 手札の交換
  container.appendChild(createBtn('② 手札の交換', () => showTargetSelect('ACTION_EXCHANGE_HAND')));

  // ③ リンゴの交換
  container.appendChild(createBtn('③ リンゴの交換', () => showTargetSelect('ACTION_EXCHANGE_APPLE')));

  // ④ 自分のリンゴの確認
  container.appendChild(createBtn('④ リンゴ確認', () => sendEvent('ACTION_CHECK_OWN_APPLE')));

  // ⑤ 能力発動
  const canAbility = (state.role === 'GRAY' || state.role === 'LIGHT');
  const btnAbility = createBtn('⑤ 能力発動', () => showAbilityUI());
  btnAbility.disabled = !canAbility;
  container.appendChild(btnAbility);
}

function renderCardChoiceUI() {
  const container = document.getElementById('game-actions');
  container.innerHTML = '<div style="width:100%;text-align:center;font-size:0.85rem;margin-bottom:8px;">手札が2枚あります。1枚を使用または廃棄してください:</div>';

  state.myHand.forEach(card => {
    const isCursedRing = card.cardType === 'CURSED_RING';
    const btnUse = createBtn(`使う: ${CARD_NAMES[card.cardType]}`, () => showCardUseUI(card));
    btnUse.disabled = isCursedRing;
    container.appendChild(btnUse);

    const btnDiscard = createBtn(`捨てる: ${CARD_NAMES[card.cardType]}`, () => sendEvent('ACTION_DISCARD_CARD', { cardId: card.cardId }));
    btnDiscard.disabled = isCursedRing;
    btnDiscard.classList.add('btn-secondary');
    container.appendChild(btnDiscard);
  });
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
    case 'KNIFE':
    case 'ROPE':
    case 'POISON_COMB':
      // Target 1 player (including self for questions)
      const targets = card.cardType === 'APPLE_QUESTION' || card.cardType === 'MUSHROOM_QUESTION'
        ? gs.players.filter(p => p.isAlive)
        : alivePlayers;
      targets.forEach(p => {
        container.appendChild(createBtn(p.userName, () =>
          sendEvent('ACTION_USE_CARD', { cardId: card.cardId, cardType: card.cardType, params: { targetPlayerId: p.playerId } })
        ));
      });
      break;

    case 'ITADAKIMASU':
      const maxDiscard = Math.min(3, gs.deckRemainingCount);
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

// === Game Result ===
function showGameResult(payload) {
  showScreen('result');
  const title = document.getElementById('result-title');
  if (payload.reason === 'SNOW_WHITE_DISCONNECTED') {
    title.textContent = '白雪姫が切断したためゲームが終了しました';
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
    const list = document.getElementById('player-list');
    list.innerHTML = '';
    data.players.forEach(p => {
      const li = document.createElement('li');
      li.textContent = p.userName;
      if (p.isHost) li.innerHTML += ' <span class="badge badge-host">ホスト</span>';
      if (!p.isConnected) li.innerHTML += ' <span class="badge badge-disconnected">切断中</span>';
      list.appendChild(li);
    });
    document.getElementById('player-count').textContent = data.players.length;

    // Enable start button when player count matches role count
    if (state.isHost) {
      const roleCount = state.selectedRoles.length;
      const btn = document.getElementById('btn-start-game');
      btn.disabled = roleCount === 0 || data.players.length !== roleCount;
    }
  } catch(e) { console.error(e); }
}

// === Username validation ===
document.getElementById('input-username').addEventListener('input', (e) => {
  const valid = e.target.value.trim().length > 0;
  document.getElementById('btn-create-room').disabled = !valid;
  document.getElementById('btn-join-room').disabled = !valid;
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
    document.getElementById('waiting-msg').style.display = 'none';
    showScreen('waiting');
    connectWs();
    pollRoom();
    state.pollInterval = setInterval(pollRoom, 3000);
  } catch(e) { alert(e.message); }
};

document.getElementById('btn-do-join').onclick = async () => {
  const code = document.getElementById('input-room-code').value.trim().toUpperCase();
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

document.getElementById('btn-start-game').onclick = () => {
  sendEvent('GAME_START', {});
};

document.getElementById('btn-rematch').onclick = () => {
  sendEvent('REMATCH_REQUEST', {});
};

const btnBackToStart = document.getElementById('btn-back-to-start');
if (btnBackToStart) btnBackToStart.onclick = () => { resetAndGoHome(); };
document.getElementById('btn-disband').onclick = () => { resetAndGoHome(); };
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

