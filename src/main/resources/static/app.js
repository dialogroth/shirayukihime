// === State ===
let state = { screen: 'start', username: '', roomId: '', roomCode: '', playerId: '', isHost: false, ws: null };

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
  if (!res.ok) throw new Error(data.message || 'Error');
  return data;
}

// === WebSocket ===
function connectWs() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const url = `${proto}//${location.host}/ws/game?roomId=${state.roomId}&playerId=${state.playerId}`;
  const ws = new WebSocket(url);
  state.ws = ws;

  ws.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    handleServerEvent(msg);
  };
  ws.onclose = () => { log('接続が切れました'); };
}

function sendEvent(type, payload = {}) {
  if (state.ws && state.ws.readyState === WebSocket.OPEN) {
    state.ws.send(JSON.stringify({ type, payload }));
  }
}

// === Server event handler ===
function handleServerEvent(msg) {
  const { type, payload } = msg;
  log(`[${type}]`);

  switch (type) {
    case 'PLAYER_JOINED':
      addPlayerToList(payload.playerName || payload.userName);
      break;
    case 'GAME_STARTED':
    case 'GAME_STATE_SYNC':
      showScreen('game');
      renderGameState(payload);
      break;
    case 'PHASE_CHANGED':
      document.getElementById('phase-label').textContent =
        payload.newPhase === 'LAST_TURN' ? '最後の手番フェイズ' : 'ストーリーフェイズ';
      break;
    case 'REQUEST_PREFERENCE':
      showPreferenceUI(payload);
      break;
    case 'NOTIFY_PLAYER_DIED':
      markPlayerDead(payload.playerId);
      break;
    case 'GAME_ENDED':
    case 'RESULT':
      showResult(payload);
      break;
    case 'ERROR':
      alert(payload.message || 'エラーが発生しました');
      break;
    default:
      log(JSON.stringify(msg));
  }
}

// === Render functions ===
function renderGameState(gs) {
  if (!gs) return;
  // Players
  const container = document.getElementById('game-players');
  if (gs.players) {
    container.innerHTML = gs.players.map(p => {
      let cls = 'player-chip';
      if (p.isCurrent) cls += ' current';
      if (p.isDead) cls += ' dead';
      return `<span class="${cls}">${p.name || p.userName}${p.isCurrent ? ' ◀' : ''}</span>`;
    }).join('');
  }
  // Turn
  if (gs.currentPlayerName) {
    document.getElementById('turn-label').textContent = gs.currentPlayerName + ' のターン';
  }
  // Deck
  if (gs.deckCount !== undefined) {
    document.getElementById('deck-info').textContent = `山札: ${gs.deckCount}枚`;
  }
  // My apple
  if (gs.myApple) {
    document.getElementById('my-apple').textContent = gs.myApple.isPoisoned ? '🍎 毒リンゴ' : '🍏 リンゴ';
  }
  // Actions
  renderActions(gs);
}

function renderActions(gs) {
  const container = document.getElementById('game-actions');
  container.innerHTML = '';
  if (!gs.isMyTurn) return;

  if (gs.drawnCard) {
    // Show card and action buttons
    const cardDiv = document.createElement('div');
    cardDiv.textContent = `引いたカード: ${gs.drawnCard.name}`;
    container.appendChild(cardDiv);

    if (gs.availableTargets && gs.availableTargets.length > 0) {
      gs.availableTargets.forEach(t => {
        const btn = document.createElement('button');
        btn.textContent = t.name || t.userName;
        btn.onclick = () => sendEvent('ACTION_USE_CARD', { targetPlayerId: t.id });
        container.appendChild(btn);
      });
    }
  } else {
    const btn = document.createElement('button');
    btn.textContent = 'カードを引く';
    btn.onclick = () => sendEvent('ACTION_DRAW_CARD', {});
    container.appendChild(btn);
  }
}

function showPreferenceUI(payload) {
  const container = document.getElementById('game-actions');
  container.innerHTML = '<p>好みを選んでください:</p>';
  ['好き', '嫌い'].forEach(answer => {
    const btn = document.createElement('button');
    btn.textContent = answer;
    btn.onclick = () => sendEvent('RESPONSE_PREFERENCE', { answer });
    container.appendChild(btn);
  });
}

function markPlayerDead(playerId) {
  // handled on next GAME_STATE_SYNC
  log(`プレイヤーが死亡しました`);
}

function showResult(payload) {
  showScreen('result');
  document.getElementById('result-title').textContent = payload.winnerFaction ? `${payload.winnerFaction} の勝利！` : 'ゲーム終了';
  const details = document.getElementById('result-details');
  if (payload.players) {
    details.innerHTML = payload.players.map(p =>
      `<div>${p.name}: ${p.role} ${p.isWinner ? '🏆' : ''}</div>`
    ).join('');
  }
}

function addPlayerToList(name) {
  const li = document.createElement('li');
  li.textContent = name;
  document.getElementById('player-list').appendChild(li);
}

function log(msg) {
  const el = document.getElementById('game-log');
  if (el) {
    el.innerHTML += `<div>${msg}</div>`;
    el.scrollTop = el.scrollHeight;
  }
}

// === Room polling ===
async function pollRoom() {
  try {
    const data = await api('GET', '/' + state.roomCode);
    const list = document.getElementById('player-list');
    list.innerHTML = '';
    data.players.forEach(p => {
      const li = document.createElement('li');
      li.textContent = p.userName + (p.isHost ? ' (ホスト)' : '');
      list.appendChild(li);
    });
  } catch(e) {}
}

// === Event listeners ===
document.getElementById('btn-create-room').onclick = () => {
  state.username = document.getElementById('input-username').value.trim();
  if (!state.username) return alert('名前を入力してください');
  showScreen('create');
};

document.getElementById('btn-join-room').onclick = () => {
  state.username = document.getElementById('input-username').value.trim();
  if (!state.username) return alert('名前を入力してください');
  showScreen('join');
};

document.getElementById('btn-do-create').onclick = async () => {
  const poisonCount = parseInt(document.getElementById('input-poison-count').value) || 2;
  try {
    const data = await api('POST', '', {
      userName: state.username,
      poisonAppleCount: poisonCount,
      roles: [],
      cardSettings: []
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
    setInterval(pollRoom, 3000);
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
    setInterval(pollRoom, 3000);
  } catch(e) { alert(e.message); }
};

document.getElementById('btn-start-game').onclick = () => {
  sendEvent('GAME_START', {});
};

document.getElementById('btn-back-to-start').onclick = () => {
  if (state.ws) state.ws.close();
  state = { screen: 'start', username: '', roomId: '', roomCode: '', playerId: '', isHost: false, ws: null };
  showScreen('start');
};

document.querySelectorAll('.btn-back').forEach(btn => {
  btn.onclick = () => showScreen(btn.dataset.back);
});

