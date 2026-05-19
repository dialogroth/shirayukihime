package service

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import model.enums.*
import model.game.*
import model.ws.*
import repository.PlayerRepository
import repository.RoomRepository
import websocket.ConnectionManager
import java.util.UUID

class GameService(
    private val connectionManager: ConnectionManager,
    private val roomRepository: RoomRepository,
    private val playerRepository: PlayerRepository,
    private val scope: CoroutineScope
) {
    private val turnTimerJobs = mutableMapOf<UUID, Job>()
    private val preferenceTimerJobs = mutableMapOf<UUID, Job>()
    private val queenExchangeTimerJobs = mutableMapOf<UUID, Job>()
    private val disconnectTimerJobs = mutableMapOf<UUID, Job>() // playerId -> Job
    // roomId -> playerId currently waiting for preference response
    private val pendingPreference = mutableMapOf<UUID, Pair<UUID, String>>()
    // roomId -> waiting for queen exchange
    private val pendingQueenExchange = mutableMapOf<UUID, Boolean>()
    // roomId -> waiting for host to proceed to reveal
    private val pendingProceedToReveal = mutableMapOf<UUID, Boolean>()
    // roomId -> waiting for host to proceed to result
    private val pendingProceedToResult = mutableMapOf<UUID, Boolean>()
    private val proceedToRevealTimerJobs = mutableMapOf<UUID, Job>()
    private val proceedToResultTimerJobs = mutableMapOf<UUID, Job>()

    // ── ゲーム初期化 ──────────────────────────────────────────────────

    private fun cleanupGameState(roomId: UUID) {
        turnTimerJobs.remove(roomId)?.cancel()
        preferenceTimerJobs.remove(roomId)?.cancel()
        queenExchangeTimerJobs.remove(roomId)?.cancel()
        proceedToRevealTimerJobs.remove(roomId)?.cancel()
        proceedToResultTimerJobs.remove(roomId)?.cancel()
        pendingPreference.remove(roomId)
        pendingQueenExchange.remove(roomId)
        pendingProceedToReveal.remove(roomId)
        pendingProceedToResult.remove(roomId)
    }

    suspend fun initGame(roomId: UUID, hostPlayerId: UUID? = null) {
        // 前回ゲームの残留状態をクリア（再ゲーム対応）
        cleanupGameState(roomId)

        val settings = roomRepository.findSettings(roomId)
        if (settings == null) {
            if (hostPlayerId != null) sendError(roomId, hostPlayerId, "SETTINGS_NOT_FOUND", "ルーム設定が見つかりません")
            return
        }
        val players = playerRepository.findByRoomId(roomId)

        if (players.isEmpty()) {
            if (hostPlayerId != null) sendError(roomId, hostPlayerId, "NO_PLAYERS", "プレイヤーがいません")
            return
        }

        val roles = settings.roles.map { Role.valueOf(it) }.shuffled()
        if (roles.size != players.size) {
            if (hostPlayerId != null) sendError(roomId, hostPlayerId, "PLAYER_ROLE_MISMATCH",
                "プレイヤー数(${players.size})と役職数(${roles.size})が一致しません")
            return
        }

        val gamePlayers = players.mapIndexed { i, p ->
            val role = roles[i]
            GamePlayer(
                playerId = p.id,
                userName = p.userName,
                seatOrder = p.seatOrder,
                role = role,
                faction = role.faction()
            )
        }.associateBy { it.playerId }

        val apples = buildApples(gamePlayers.values.toList(), settings.poisonAppleCount)
        val (cards, deckOrder) = buildDeck(settings, gamePlayers.values.toList())

        val turnOrder = buildTurnOrder(gamePlayers.values.toList())
        val firstTurnIndex = 0

        val gameId = UUID.randomUUID()
        val state = GameState(
            gameId = gameId,
            roomId = roomId,
            phase = GamePhase.STORY,
            turnOrder = turnOrder,
            currentTurnIndex = firstTurnIndex,
            lastTurnStartPlayerIndex = null,
            players = gamePlayers,
            apples = apples,
            cards = cards,
            deckOrder = deckOrder,
            discardOrder = emptyList()
        )

        GameStateManager.set(roomId, state)
        roomRepository.updateStatus(roomId, RoomStatus.IN_GAME)

        broadcastGameStarted(roomId, state)
        sendAllInitialInfo(roomId, state)
        broadcastGameStateSync(roomId, state)
        startTurn(roomId)
    }

    private fun buildApples(players: List<GamePlayer>, poisonCount: Int): List<Apple> {
        val poisonedFlags = (List(poisonCount) { true } + List(players.size - poisonCount) { false }).shuffled()
        return players.mapIndexed { i, player ->
            val isPoisoned = poisonedFlags[i]
            val knownBy = mutableSetOf(player.playerId)
            Apple(
                appleId = UUID.randomUUID(),
                isPoisoned = isPoisoned,
                currentHolderPlayerId = player.playerId,
                privatelyKnownBy = knownBy
            )
        }
    }

    private fun buildDeck(
        settings: repository.RoomSettingRow,
        players: List<GamePlayer>
    ): Pair<Map<UUID, GameCard>, List<UUID>> {
        val defaultCounts = mapOf(
            CardType.APPLE_QUESTION to 5, CardType.MUSHROOM_QUESTION to 5,
            CardType.ITADAKIMASU to 3, CardType.ROULETTE_1 to 1,
            CardType.ROULETTE_2 to 1, CardType.ROULETTE_3 to 1,
            CardType.KNIFE to 1, CardType.CURSED_RING to 1,
            CardType.POISON_COMB to 1, CardType.KNIGHT to 1,
            CardType.GUARD to 1, CardType.ROPE to 1,
            CardType.PRESENT_EXCHANGE to 1
        )
        val countMap = defaultCounts.toMutableMap()
        settings.cardSettings.forEach { countMap[it.cardType] = it.count }

        val allCards = countMap.flatMap { (type, count) ->
            (0 until count).map { GameCard(cardId = UUID.randomUUID(), cardType = type, location = CardLocation.DECK) }
        }.shuffled()

        val cards = allCards.associateBy { it.cardId }.toMutableMap()
        val deckOrder = allCards.map { it.cardId }.toMutableList()

        // 各プレイヤーに手札を1枚ずつ配る
        players.forEach { player ->
            val cardId = deckOrder.removeFirst()
            cards[cardId] = cards[cardId]!!.copy(
                location = CardLocation.HAND,
                holderPlayerId = player.playerId
            )
        }

        return cards to deckOrder
    }

    private fun buildTurnOrder(players: List<GamePlayer>): List<UUID> {
        return players.sortedBy { it.seatOrder }.map { it.playerId }
    }

    // ── ターン管理 ──────────────────────────────────────────────────

    private suspend fun startTurn(roomId: UUID) {
        val state = GameStateManager.get(roomId) ?: return
        val currentPlayerId = state.currentTurnPlayerId()
        val player = state.players[currentPlayerId] ?: return


        // ロープスキップチェック
        if (player.skipNextTurn) {
            val updated = GameStateManager.update(roomId) { s ->
                s.copy(players = s.players + (currentPlayerId to player.copy(skipNextTurn = false)))
            } ?: return
            broadcast(roomId, EventType.NOTIFY_PLAYER_SKIPPED, NotifyPlayerSkippedPayload(currentPlayerId.toString()))
            broadcastGameStateSync(roomId, updated)
            advanceTurn(roomId)
            return
            // 注：グレイの保護はスキップされた手番では解除されない（要件通り）
        }

        // 呪いの指輪チェック：最後の手番フェイズで手番が回ってきた瞬間に持っていれば即死
        if (state.phase == GamePhase.LAST_TURN) {
            val hasCursedRing = state.handOf(currentPlayerId).any { it.cardType == CardType.CURSED_RING }
            if (hasCursedRing) {
                killPlayer(roomId, currentPlayerId, "CURSED_RING")
                val newState = GameStateManager.get(roomId) ?: return
                broadcastGameStateSync(roomId, newState)
                if (newState.phase == GamePhase.FINISHED) return
                advanceTurn(roomId)
                return
            }
        }

        // グレイの保護リセット（スキップされなかった本来の手番開始時）
        if (player.role == Role.GRAY && player.isProtected) {
            GameStateManager.update(roomId) { s ->
                s.copy(players = s.players + (currentPlayerId to player.copy(isProtected = false)))
            }
        }

        // 切断プレイヤーの手番（1分待って死亡扱い）
        if (!player.isConnected) {
            val job = scope.launch {
                delay(60_000)
                handleDisconnectTimeout(roomId, currentPlayerId)
            }
            disconnectTimerJobs[currentPlayerId] = job
            return
        }

        broadcastTurnChanged(roomId, currentPlayerId)
        startTurnTimer(roomId, currentPlayerId)
    }

    private suspend fun advanceTurn(roomId: UUID) {
        cancelTurnTimer(roomId)
        val state = GameStateManager.get(roomId) ?: return

        when (state.phase) {
            GamePhase.STORY -> advanceStoryTurn(roomId, state)
            GamePhase.LAST_TURN -> advanceLastTurn(roomId, state)
            else -> {}
        }
    }

    private suspend fun advanceStoryTurn(roomId: UUID, state: GameState) {
        // 山札が尽きた場合はすでにLAST_TURNに移行済み（handleDrawCard / handleItadakimasuで処理）
        val nextIndex = nextAliveIndex(state, state.currentTurnIndex)
        val newState = GameStateManager.update(roomId) { it.copy(currentTurnIndex = nextIndex) } ?: return
        startTurn(roomId)
    }

    private suspend fun advanceLastTurn(roomId: UUID, state: GameState) {
        // 現在のプレイヤーをプレイ済みに追加
        val currentPlayerId = state.currentTurnPlayerId()
        val playedSet = state.lastTurnPlayersPlayed + currentPlayerId
        GameStateManager.update(roomId) { it.copy(lastTurnPlayersPlayed = playedSet) }

        // 全ての生存プレイヤーがプレイ済みなら一周完了 → エンディングへ
        val alivePlayerIds = state.players.values.filter { it.isAlive }.map { it.playerId }.toSet()
        if (alivePlayerIds.all { it in playedSet }) {
            startEndingPhase(roomId)
            return
        }

        val nextIndex = nextAliveIndex(state, state.currentTurnIndex)
        GameStateManager.update(roomId) { it.copy(currentTurnIndex = nextIndex) }
        startTurn(roomId)
    }

    private fun nextAliveIndex(state: GameState, currentIndex: Int): Int {
        val size = state.turnOrder.size
        var next = (currentIndex + 1) % size
        var checked = 0
        while (checked < size) {
            val pid = state.turnOrder[next]
            val p = state.players[pid]
            if (p != null && p.isAlive) return next
            next = (next + 1) % size
            checked++
        }
        return next
    }

    private suspend fun transitionToLastTurn(roomId: UUID, triggerPlayerId: UUID) {
        val state = GameStateManager.update(roomId) { s ->
            val triggerIndex = s.turnOrder.indexOf(triggerPlayerId)
            val nextIdx = nextAliveIndex(s, triggerIndex)
            s.copy(
                phase = GamePhase.LAST_TURN,
                currentTurnIndex = nextIdx,
                lastTurnStartPlayerIndex = nextIdx,
                lastTurnPlayersPlayed = emptySet()
            )
        } ?: return

        broadcast(roomId, EventType.PHASE_CHANGED, PhaseChangedPayload("LAST_TURN", triggerPlayerId.toString()))


        val updatedState = GameStateManager.get(roomId) ?: return
        broadcastGameStateSync(roomId, updatedState)
        startTurn(roomId)
    }

    // ── タイムアウト ──────────────────────────────────────────────────

    private fun startTurnTimer(roomId: UUID, playerId: UUID) {
        cancelTurnTimer(roomId)
        turnTimerJobs[roomId] = scope.launch {
            delay(180_000)
            handleTurnTimeout(roomId, playerId)
        }
    }

    private fun cancelTurnTimer(roomId: UUID) {
        turnTimerJobs.remove(roomId)?.cancel()
    }

    suspend fun handleThinkTime(roomId: UUID, playerId: UUID) {
        val state = GameStateManager.get(roomId) ?: return sendError(roomId, playerId, "GAME_NOT_STARTED", "ゲームが開始されていません")
        if (!isCurrentTurn(state, playerId)) return sendError(roomId, playerId, "NOT_YOUR_TURN", "自分の手番ではありません")
        val player = state.players[playerId] ?: return
        if (player.thinkTimeUsed) return sendError(roomId, playerId, "ALREADY_USED", "長考は1ゲームに1回のみ使用可能です")

        // プレイヤーの長考フラグを更新
        GameStateManager.update(roomId) { s ->
            s.copy(players = s.players + (playerId to player.copy(thinkTimeUsed = true)))
        }

        // ターンタイマーをキャンセルして+2分で再スタート
        cancelTurnTimer(roomId)
        turnTimerJobs[roomId] = scope.launch {
            delay(120_000)
            handleTurnTimeout(roomId, playerId)
        }

        broadcast(roomId, EventType.NOTIFY_THINK_TIME, NotifyThinkTimePayload(playerId.toString(), 120))
    }

    private suspend fun handleTurnTimeout(roomId: UUID, playerId: UUID) {
        val state = GameStateManager.get(roomId) ?: return
        if (state.currentTurnPlayerId() != playerId) return

        val hand = state.handOf(playerId)
        val cursedRing = hand.find { it.cardType == CardType.CURSED_RING }

        val cardToDiscard = when {
            hand.size == 1 && cursedRing != null -> null // 呪いの指輪のみ→何もしない
            hand.size == 2 && cursedRing != null -> hand.first { it.cardId != cursedRing.cardId }
            hand.isNotEmpty() -> hand.first()
            else -> null
        }

        if (cardToDiscard != null) {
            val discarded = discardCard(roomId, cardToDiscard.cardId) ?: return
            broadcast(roomId, EventType.NOTIFY_TIMEOUT, NotifyTimeoutPayload(
                timeoutType = "TURN",
                playerId = playerId.toString(),
                autoAction = "CARD_DISCARDED",
                discardedCardType = discarded.cardType.name
            ))
        }

        val newState = GameStateManager.get(roomId) ?: return
        broadcastGameStateSync(roomId, newState)
        advanceTurn(roomId)
    }

    private suspend fun handleDisconnectTimeout(roomId: UUID, playerId: UUID) {
        val state = GameStateManager.get(roomId) ?: return
        val player = state.players[playerId] ?: return
        if (player.isConnected) return

        if (player.role == Role.SNOW_WHITE) {
            forceEndGameBySnowWhiteDisconnect(roomId, playerId)
        } else {
            killPlayer(roomId, playerId, "DISCONNECTED")
            val newState = GameStateManager.get(roomId) ?: return
            broadcastGameStateSync(roomId, newState)
            if (newState.currentTurnPlayerId() == playerId) {
                advanceTurn(roomId)
            }
        }
    }

    // ── アクション処理 ────────────────────────────────────────────────

    suspend fun handleDrawCard(roomId: UUID, playerId: UUID) {
        val state = GameStateManager.get(roomId) ?: return sendError(roomId, playerId, "GAME_NOT_STARTED", "ゲームが開始されていません")
        if (!isCurrentTurn(state, playerId)) return sendError(roomId, playerId, "NOT_YOUR_TURN", "自分の手番ではありません")
        if (state.deckOrder.isEmpty()) return sendError(roomId, playerId, "INVALID_PHASE", "山札がありません")
        if (state.deckOrder.size <= 1) return sendError(roomId, playerId, "INVALID_PHASE", "山札の最後の1枚は引けません")

        val cardId = state.deckOrder.first()
        val newState = GameStateManager.update(roomId) { s ->
            val newCards = s.cards + (cardId to s.cards[cardId]!!.copy(
                location = CardLocation.HAND,
                holderPlayerId = playerId
            ))
            s.copy(cards = newCards, deckOrder = s.deckOrder.drop(1))
        } ?: return

        cancelTurnTimer(roomId)

        broadcast(roomId, EventType.NOTIFY_DRAW_CARD, NotifyDrawCardPayload(
            playerId.toString(), newState.deckOrder.size
        ))

        // 手番プレイヤーのみに手札を含むSYNCを送信
        sendGameStateSyncTo(roomId, playerId, newState)
        broadcastGameStateSyncExcept(roomId, playerId, newState)

        // 山札が残り1枚になったらLAST_TURNへ（次のプレイヤーから開始）
        if (newState.deckOrder.size == 1 && newState.phase == GamePhase.STORY) {
            GameStateManager.update(roomId) { s ->
                s.copy(
                    phase = GamePhase.LAST_TURN,
                    lastTurnStartPlayerIndex = s.currentTurnIndex
                )
            }
            broadcast(roomId, EventType.PHASE_CHANGED, PhaseChangedPayload("LAST_TURN", playerId.toString()))


            startTurnTimer(roomId, playerId)
        } else {
            startTurnTimer(roomId, playerId)
        }
    }

    suspend fun handleUseCard(
        roomId: UUID,
        playerId: UUID,
        cardId: UUID,
        cardType: CardType,
        params: Map<String, String>
    ) {
        val state = GameStateManager.get(roomId) ?: return sendError(roomId, playerId, "GAME_NOT_STARTED", "ゲームが開始されていません")
        if (!isCurrentTurn(state, playerId)) return sendError(roomId, playerId, "NOT_YOUR_TURN", "自分の手番ではありません")

        val card = state.cards[cardId] ?: return sendError(roomId, playerId, "CARD_NOT_IN_HAND", "そのカードを持っていません")
        if (card.holderPlayerId != playerId || card.location != CardLocation.HAND)
            return sendError(roomId, playerId, "CARD_NOT_IN_HAND", "そのカードを持っていません")

        if (cardType == CardType.CURSED_RING)
            return sendError(roomId, playerId, "INVALID_PHASE", "呪いの指輪は使用できません")

        if (cardType == CardType.GUARD || cardType == CardType.KNIGHT)
            return sendError(roomId, playerId, "INVALID_PHASE", "このカードは使用できません（受動効果のみ）")

        if (cardType == CardType.POISON_COMB && state.phase == GamePhase.STORY)
            return sendError(roomId, playerId, "INVALID_PHASE", "毒の櫛は最後の手番フェイズ専用です")

        broadcast(roomId, EventType.NOTIFY_USE_CARD, NotifyUseCardPayload(playerId.toString(), cardType.name, params))

        when (cardType) {
            CardType.APPLE_QUESTION, CardType.MUSHROOM_QUESTION -> {
                val targetId = UUID.fromString(params["targetPlayerId"] ?: return)
                val targetPlayer = state.players[targetId] ?: return sendError(roomId, playerId, "INVALID_TARGET", "無効なターゲットです")
                // 既に同じ質問に答えたプレイヤーには質問できない
                if (cardType == CardType.APPLE_QUESTION && targetPlayer.applePreferenceAnswer != null)
                    return sendError(roomId, playerId, "ALREADY_ANSWERED", "そのプレイヤーは既にリンゴの質問に答えています")
                if (cardType == CardType.MUSHROOM_QUESTION && targetPlayer.mushroomPreferenceAnswer != null)
                    return sendError(roomId, playerId, "ALREADY_ANSWERED", "そのプレイヤーは既にきのこの質問に答えています")
                moveCardToDiscard(roomId, cardId)
                requestPreference(roomId, playerId, targetId, cardType)
                return // ターン終了はレスポンス受信後
            }
            CardType.ITADAKIMASU -> {
                val count = params["count"]?.toIntOrNull() ?: 1
                val maxDiscardable = maxOf(0, state.deckOrder.size - 1)
                if (maxDiscardable == 0) return sendError(roomId, playerId, "INVALID_ACTION", "山札の最後の1枚は捨てられません")
                if (handleItadakimasu(roomId, playerId, cardId, count)) return
            }
            CardType.ROULETTE_1, CardType.ROULETTE_2, CardType.ROULETTE_3 -> {
                val direction = params["direction"] ?: return
                val steps = when (cardType) { CardType.ROULETTE_2 -> 2; CardType.ROULETTE_3 -> 3; else -> 1 }
                handleRoulette(roomId, playerId, cardId, direction, steps)
            }
            CardType.KNIFE -> {
                val targetId = UUID.fromString(params["targetPlayerId"] ?: return)
                handleKnife(roomId, playerId, cardId, targetId)
            }
            CardType.ROPE -> {
                val targetId = UUID.fromString(params["targetPlayerId"] ?: return)
                handleRope(roomId, playerId, cardId, targetId)
            }
            CardType.PRESENT_EXCHANGE -> {
                val targetA = UUID.fromString(params["targetPlayerIdA"] ?: return)
                val targetB = UUID.fromString(params["targetPlayerIdB"] ?: return)
                handlePresentExchange(roomId, playerId, cardId, targetA, targetB)
            }
            CardType.POISON_COMB -> {
                val targetId = UUID.fromString(params["targetPlayerId"] ?: return)
                handlePoisonComb(roomId, playerId, cardId, targetId)
            }
            else -> return
        }

        val newState = GameStateManager.get(roomId) ?: return
        broadcastGameStateSync(roomId, newState)
        if (newState.phase == GamePhase.FINISHED) return
        advanceTurn(roomId)
    }

    suspend fun handleDiscardCard(roomId: UUID, playerId: UUID, cardId: UUID) {
        val state = GameStateManager.get(roomId) ?: return sendError(roomId, playerId, "GAME_NOT_STARTED", "ゲームが開始されていません")
        if (!isCurrentTurn(state, playerId)) return sendError(roomId, playerId, "NOT_YOUR_TURN", "自分の手番ではありません")

        val card = state.cards[cardId] ?: return sendError(roomId, playerId, "CARD_NOT_IN_HAND", "そのカードを持っていません")
        if (card.cardType == CardType.CURSED_RING) return sendError(roomId, playerId, "CANNOT_DISCARD_CURSED_RING", "呪いの指輪は捨てられません")

        discardCard(roomId, cardId)
        val newState = GameStateManager.get(roomId) ?: return
        broadcast(roomId, EventType.NOTIFY_DISCARD_CARD, NotifyDiscardCardPayload(playerId.toString(), card.cardType.name))
        broadcastGameStateSync(roomId, newState)
        advanceTurn(roomId)
    }

    suspend fun handleExchangeHand(roomId: UUID, playerId: UUID, targetId: UUID) {
        val state = GameStateManager.get(roomId) ?: return sendError(roomId, playerId, "GAME_NOT_STARTED", "ゲームが開始されていません")
        if (!isCurrentTurn(state, playerId)) return sendError(roomId, playerId, "NOT_YOUR_TURN", "自分の手番ではありません")

        val target = state.players[targetId] ?: return sendError(roomId, playerId, "INVALID_TARGET", "無効なターゲットです")
        if (!target.isAlive) return sendError(roomId, playerId, "INVALID_TARGET", "そのプレイヤーは死亡しています")
        if (target.isProtected) return sendError(roomId, playerId, "TARGET_IS_PROTECTED", "そのプレイヤーはグレイに保護されています")

        val myHand = state.handOf(playerId)
        val targetHand = state.handOf(targetId)

        GameStateManager.update(roomId) { s ->
            val updatedCards = s.cards.toMutableMap()
            myHand.forEach { updatedCards[it.cardId] = it.copy(holderPlayerId = targetId) }
            targetHand.forEach { updatedCards[it.cardId] = it.copy(holderPlayerId = playerId) }
            s.copy(cards = updatedCards)
        }

        broadcast(roomId, EventType.NOTIFY_EXCHANGE_HAND, NotifyExchangeHandPayload(playerId.toString(), targetId.toString()))
        val newState = GameStateManager.get(roomId) ?: return
        broadcastGameStateSync(roomId, newState)
        advanceTurn(roomId)
    }

    suspend fun handleExchangeApple(roomId: UUID, playerId: UUID, targetId: UUID) {
        val state = GameStateManager.get(roomId) ?: return sendError(roomId, playerId, "GAME_NOT_STARTED", "ゲームが開始されていません")
        if (!isCurrentTurn(state, playerId)) return sendError(roomId, playerId, "NOT_YOUR_TURN", "自分の手番ではありません")

        val target = state.players[targetId] ?: return sendError(roomId, playerId, "INVALID_TARGET", "無効なターゲットです")
        if (!target.isAlive) return sendError(roomId, playerId, "INVALID_TARGET", "そのプレイヤーは死亡しています")
        if (target.isProtected) return sendError(roomId, playerId, "TARGET_IS_PROTECTED", "そのプレイヤーはグレイに保護されています")

        swapApples(roomId, playerId, targetId)
        broadcast(roomId, EventType.NOTIFY_EXCHANGE_APPLE, NotifyExchangeApplePayload(playerId.toString(), targetId.toString()))
        notifyBlackIfPresent(roomId)
        val newState = GameStateManager.get(roomId) ?: return
        broadcastGameStateSync(roomId, newState)
        advanceTurn(roomId)
    }

    suspend fun handleCheckOwnApple(roomId: UUID, playerId: UUID) {
        val state = GameStateManager.get(roomId) ?: return sendError(roomId, playerId, "GAME_NOT_STARTED", "ゲームが開始されていません")
        if (!isCurrentTurn(state, playerId)) return sendError(roomId, playerId, "NOT_YOUR_TURN", "自分の手番ではありません")

        val apple = state.appleOf(playerId) ?: return
        GameStateManager.update(roomId) { s ->
            val updatedApples = s.apples.map {
                if (it.currentHolderPlayerId == playerId) it.copy(privatelyKnownBy = it.privatelyKnownBy + playerId)
                else it
            }
            s.copy(apples = updatedApples)
        }

        sendTo(roomId, playerId, EventType.YOUR_APPLE_STATUS, YourAppleStatusPayload(
            appleId = apple.appleId.toString(),
            isPoisoned = apple.isPoisoned
        ))
        val newState = GameStateManager.get(roomId) ?: return
        broadcastGameStateSync(roomId, newState)
        advanceTurn(roomId)
    }

    suspend fun handleUseAbility(roomId: UUID, playerId: UUID, params: Map<String, String>) {
        val state = GameStateManager.get(roomId) ?: return sendError(roomId, playerId, "GAME_NOT_STARTED", "ゲームが開始されていません")
        if (!isCurrentTurn(state, playerId)) return sendError(roomId, playerId, "NOT_YOUR_TURN", "自分の手番ではありません")

        val player = state.players[playerId] ?: return
        when (player.role) {
            Role.GRAY -> handleGrayAbility(roomId, playerId, player)
            Role.LIGHT -> handleLightAbility(roomId, playerId, player, params)
            else -> sendError(roomId, playerId, "INVALID_PHASE", "この役職には手番能力がありません")
        }
    }

    private suspend fun handleGrayAbility(roomId: UUID, playerId: UUID, player: GamePlayer) {
        if (player.grayAbilityUsed) return sendError(roomId, playerId, "ABILITY_ALREADY_USED", "能力は既に使用済みです")

        GameStateManager.update(roomId) { s ->
            s.copy(players = s.players + (playerId to player.copy(grayAbilityUsed = true, isProtected = true)))
        }
        broadcast(roomId, EventType.NOTIFY_GRAY_ABILITY_ACTIVATED, NotifyGrayAbilityActivatedPayload(playerId.toString()))
        val newState = GameStateManager.get(roomId) ?: return
        broadcastGameStateSync(roomId, newState)
        advanceTurn(roomId)
    }

    private suspend fun handleLightAbility(roomId: UUID, playerId: UUID, player: GamePlayer, params: Map<String, String>) {
        if (player.lightAbilityUsed) return sendError(roomId, playerId, "ABILITY_ALREADY_USED", "能力は既に使用済みです")

        val targetA = UUID.fromString(params["targetPlayerIdA"] ?: return)
        val targetB = UUID.fromString(params["targetPlayerIdB"] ?: return)

        val state = GameStateManager.get(roomId) ?: return
        val a = state.players[targetA]
        val b = state.players[targetB]

        if (a?.isProtected == true || b?.isProtected == true)
            return sendError(roomId, playerId, "TARGET_IS_PROTECTED", "そのプレイヤーはグレイに保護されています")

        swapApples(roomId, targetA, targetB)
        GameStateManager.update(roomId) { s ->
            s.copy(players = s.players + (playerId to player.copy(lightAbilityUsed = true, isRoleRevealed = true)))
        }

        broadcast(roomId, EventType.NOTIFY_LIGHT_ABILITY_ACTIVATED, NotifyLightAbilityActivatedPayload(
            playerId = playerId.toString(), role = "LIGHT",
            swappedPlayerIdA = targetA.toString(), swappedPlayerIdB = targetB.toString()
        ))
        notifyBlackIfPresent(roomId)
        val newState = GameStateManager.get(roomId) ?: return
        broadcastGameStateSync(roomId, newState)
        advanceTurn(roomId)
    }

    // ── カード効果 ────────────────────────────────────────────────────

    // true を返した場合は内部でフェイズ遷移済みのためターン進行不要
    private suspend fun handleItadakimasu(roomId: UUID, playerId: UUID, cardId: UUID, count: Int): Boolean {
        val state = GameStateManager.get(roomId) ?: return false
        val maxDiscardable = maxOf(0, state.deckOrder.size - 1) // 最後の1枚は捨てられない
        val actualCount = minOf(count, maxDiscardable)
        if (actualCount == 0) return false

        moveCardToDiscard(roomId, cardId)
        val deckDrop = state.deckOrder.take(actualCount)
        GameStateManager.update(roomId) { s ->
            val updatedCards = s.cards.toMutableMap()
            deckDrop.forEach { cid -> updatedCards[cid] = updatedCards[cid]!!.copy(location = CardLocation.DISCARD) }
            s.copy(cards = updatedCards, deckOrder = s.deckOrder.drop(actualCount),
                discardOrder = s.discardOrder + deckDrop)
        }

        val newState = GameStateManager.get(roomId) ?: return false
        if (newState.deckOrder.size <= 1 && state.deckOrder.size > 1 && state.phase == GamePhase.STORY) {
            broadcastGameStateSync(roomId, newState)
            transitionToLastTurn(roomId, playerId)
            return true // フェイズ遷移済み
        }
        return false
    }

    private suspend fun handleRoulette(roomId: UUID, playerId: UUID, cardId: UUID, direction: String, steps: Int) {
        moveCardToDiscard(roomId, cardId)
        val state = GameStateManager.get(roomId) ?: return
        val alivePlayers = state.alivePlayers()
        val n = alivePlayers.size
        if (n == 0) return

        val deadIds = state.players.values.filter { !it.isAlive }.map { it.playerId.toString() }

        val shiftedApples = state.apples.map { apple ->
            val currentHolder = apple.currentHolderPlayerId
            val aliveIdx = alivePlayers.indexOfFirst { it.playerId == currentHolder }
            if (aliveIdx < 0) return@map apple
            val newIdx = when (direction) {
                "CLOCKWISE" -> (aliveIdx + steps) % n
                else -> (aliveIdx - steps + n) % n
            }
            apple.copy(currentHolderPlayerId = alivePlayers[newIdx].playerId)
        }

        GameStateManager.update(roomId) { it.copy(apples = shiftedApples) }
        broadcast(roomId, EventType.NOTIFY_ROULETTE, NotifyRoulettePayload(
            cardType = when (steps) { 2 -> "ROULETTE_2"; 3 -> "ROULETTE_3"; else -> "ROULETTE_1" },
            direction = direction, steps = steps, excludedPlayerIds = deadIds
        ))
        notifyBlackIfPresent(roomId)
    }

    private suspend fun handleKnife(roomId: UUID, playerId: UUID, cardId: UUID, targetId: UUID) {
        val state = GameStateManager.get(roomId) ?: return
        val target = state.players[targetId] ?: return sendError(roomId, playerId, "INVALID_TARGET", "無効なターゲットです")
        if (!target.isAlive) return sendError(roomId, playerId, "INVALID_TARGET", "そのプレイヤーは死亡しています")

        val apple = state.appleOf(targetId) ?: return
        GameStateManager.update(roomId) { s ->
            val updatedApples = s.apples.map {
                if (it.appleId == apple.appleId) it.copy(isPubliclyRevealed = true) else it
            }
            s.copy(apples = updatedApples)
        }
        moveCardToDiscard(roomId, cardId)
        broadcast(roomId, EventType.NOTIFY_APPLE_PUBLICLY_REVEALED, NotifyApplePubliclyRevealedPayload(
            apple.appleId.toString(), targetId.toString(), apple.isPoisoned
        ))
    }

    private suspend fun handleRope(roomId: UUID, playerId: UUID, cardId: UUID, targetId: UUID) {
        val state = GameStateManager.get(roomId) ?: return
        val target = state.players[targetId] ?: return sendError(roomId, playerId, "INVALID_TARGET", "無効なターゲットです")
        if (!target.isAlive) return sendError(roomId, playerId, "INVALID_TARGET", "そのプレイヤーは死亡しています")

        GameStateManager.update(roomId) { s ->
            s.copy(players = s.players + (targetId to target.copy(skipNextTurn = true)))
        }
        moveCardToDiscard(roomId, cardId)
    }

    private suspend fun handlePresentExchange(
        roomId: UUID, playerId: UUID, cardId: UUID, targetA: UUID, targetB: UUID
    ) {
        val state = GameStateManager.get(roomId) ?: return
        val a = state.players[targetA]
        val b = state.players[targetB]
        if (a?.isProtected == true || b?.isProtected == true)
            return sendError(roomId, playerId, "TARGET_IS_PROTECTED", "そのプレイヤーはグレイに保護されています")

        val handA = state.handOf(targetA)
        val handB = state.handOf(targetB)
        GameStateManager.update(roomId) { s ->
            val updatedCards = s.cards.toMutableMap()
            handA.forEach { updatedCards[it.cardId] = it.copy(holderPlayerId = targetB) }
            handB.forEach { updatedCards[it.cardId] = it.copy(holderPlayerId = targetA) }
            s.copy(cards = updatedCards)
        }
        moveCardToDiscard(roomId, cardId)
        broadcast(roomId, EventType.NOTIFY_EXCHANGE_HAND, NotifyExchangeHandPayload(targetA.toString(), targetB.toString()))
    }

    private suspend fun handlePoisonComb(roomId: UUID, playerId: UUID, cardId: UUID, targetId: UUID) {
        val state = GameStateManager.get(roomId) ?: return
        val target = state.players[targetId] ?: return sendError(roomId, playerId, "INVALID_TARGET", "無効なターゲットです")
        if (!target.isAlive) return sendError(roomId, playerId, "INVALID_TARGET", "そのプレイヤーは死亡しています")

        if (target.role == Role.SNOW_WHITE) {
            val hasKnight = state.handOf(targetId).any { it.cardType == CardType.KNIGHT }
            if (hasKnight) {
                moveCardToDiscard(roomId, cardId)
                broadcast(roomId, EventType.NOTIFY_KNIGHT_BLOCKED, NotifyKnightBlockedPayload(targetId.toString()))
                return
            }
        }

        moveCardToDiscard(roomId, cardId)
        killPlayer(roomId, targetId, "POISON_COMB")
    }

    // ── 好み質問 ─────────────────────────────────────────────────────

    private suspend fun requestPreference(roomId: UUID, askedBy: UUID, targetId: UUID, cardType: CardType) {
        val questionType = if (cardType == CardType.APPLE_QUESTION) "APPLE" else "MUSHROOM"
        pendingPreference[roomId] = targetId to questionType

        sendTo(roomId, targetId, EventType.REQUEST_PREFERENCE, RequestPreferencePayload(questionType, askedBy.toString()))

        preferenceTimerJobs[roomId] = scope.launch {
            delay(60_000)
            handlePreferenceTimeout(roomId, targetId, questionType, askedBy)
        }
    }

    suspend fun handlePreferenceResponse(roomId: UUID, playerId: UUID, answer: Boolean) {
        val pending = pendingPreference[roomId] ?: return
        if (pending.first != playerId) return

        preferenceTimerJobs.remove(roomId)?.cancel()
        pendingPreference.remove(roomId)

        recordPreferenceAnswer(roomId, playerId, pending.second, answer)
        val state = GameStateManager.get(roomId) ?: return
        broadcastGameStateSync(roomId, state)
        advanceTurn(roomId)
    }

    private suspend fun handlePreferenceTimeout(roomId: UUID, targetId: UUID, questionType: String, askedBy: UUID) {
        if (pendingPreference[roomId]?.first != targetId) return
        pendingPreference.remove(roomId)

        val state = GameStateManager.get(roomId) ?: return
        val player = state.players[targetId] ?: return
        val autoAnswer = when (player.role) {
            Role.SNOW_WHITE, Role.QUEEN, Role.LIGHT -> questionType == "APPLE"
            Role.GREEN, Role.BLACK, Role.GRAY -> questionType == "MUSHROOM"
            Role.BROWN -> true
            Role.ROSE -> false
            Role.NAVY -> listOf(true, false).random()
        }

        broadcast(roomId, EventType.NOTIFY_TIMEOUT, NotifyTimeoutPayload("PREFERENCE", targetId.toString(), "ANSWERED_BY_ROLE"))
        recordPreferenceAnswer(roomId, targetId, questionType, autoAnswer)
        val newState = GameStateManager.get(roomId) ?: return
        broadcastGameStateSync(roomId, newState)
        advanceTurn(roomId)
    }

    private suspend fun recordPreferenceAnswer(roomId: UUID, playerId: UUID, questionType: String, answer: Boolean) {
        GameStateManager.update(roomId) { s ->
            val player = s.players[playerId] ?: return@update s
            val updated = if (questionType == "APPLE")
                player.copy(applePreferenceAnswer = answer)
            else
                player.copy(mushroomPreferenceAnswer = answer)
            s.copy(players = s.players + (playerId to updated))
        }
        broadcast(roomId, EventType.NOTIFY_PREFERENCE_ANSWERED, NotifyPreferenceAnsweredPayload(
            playerId.toString(), questionType, answer
        ))
    }

    // ── エンディングフェイズ ───────────────────────────────────────────

    private suspend fun startEndingPhase(roomId: UUID) {
        // 女王特権フェイズへ移行
        val state = GameStateManager.update(roomId) { it.copy(phase = GamePhase.ENDING_QUEEN) } ?: return
        broadcast(roomId, EventType.PHASE_CHANGED, PhaseChangedPayload("ENDING_QUEEN", null))
        broadcastGameStateSync(roomId, state)

        val queenPlayer = state.players.values.find { it.role == Role.QUEEN }
        if (queenPlayer == null || !queenPlayer.isAlive) {
            // 女王死亡 → 即座にリンゴ公開へ
            GameStateManager.update(roomId) { it.copy(queenSpecialDone = true) }
            revealAllApplesAndProceed(roomId)
            return
        }

        val queenApple = state.appleOf(queenPlayer.playerId) ?: return revealAllApplesAndProceed(roomId)
        // 女王のリンゴを全体公開
        GameStateManager.update(roomId) { s ->
            val updatedApples = s.apples.map {
                if (it.appleId == queenApple.appleId) it.copy(isPubliclyRevealed = true) else it
            }
            s.copy(apples = updatedApples)
        }
        broadcast(roomId, EventType.NOTIFY_APPLE_PUBLICLY_REVEALED, NotifyApplePubliclyRevealedPayload(
            queenApple.appleId.toString(), queenPlayer.playerId.toString(), queenApple.isPoisoned
        ))
        val newState = GameStateManager.get(roomId) ?: return
        broadcastGameStateSync(roomId, newState)

        if (!queenApple.isPoisoned) {
            // 通常リンゴ → 交換なし
            GameStateManager.update(roomId) { it.copy(queenSpecialDone = true) }
            revealAllApplesAndProceed(roomId)
            return
        }

        // 毒リンゴ → 交換対象を選ばせる
        val targets = state.players.values
            .filter { it.isAlive && it.playerId != queenPlayer.playerId }
            .map { it.playerId.toString() }
        pendingQueenExchange[roomId] = true
        broadcastTurnChanged(roomId, queenPlayer.playerId)
        sendTo(roomId, queenPlayer.playerId, EventType.REQUEST_QUEEN_EXCHANGE,
            RequestQueenExchangePayload(targets))

        queenExchangeTimerJobs[roomId] = scope.launch {
            delay(180_000)
            handleQueenExchangeTimeout(roomId, queenPlayer.playerId)
        }
    }

    /** 女王の特権完了後：全員のリンゴを公開（死亡判定なし）してからエンディングリビールへ */
    private suspend fun revealAllApplesAndProceed(roomId: UUID) {
        val preState = GameStateManager.get(roomId) ?: return
        GameStateManager.update(roomId) { s ->
            val revealedApples = s.apples.map { it.copy(isPubliclyRevealed = true) }
            s.copy(apples = revealedApples)
        }
        // 全員にリンゴ公開を通知
        preState.apples.forEach { apple ->
            broadcast(roomId, EventType.NOTIFY_APPLE_PUBLICLY_REVEALED, NotifyApplePubliclyRevealedPayload(
                apple.appleId.toString(), apple.currentHolderPlayerId.toString(), apple.isPoisoned
            ))
        }
        val syncState = GameStateManager.get(roomId) ?: return
        broadcastGameStateSync(roomId, syncState)

        startEndingReveal(roomId)
    }

    suspend fun handleQueenExchangeResponse(roomId: UUID, queenPlayerId: UUID, targetId: UUID) {
        if (pendingQueenExchange[roomId] != true) return
        pendingQueenExchange.remove(roomId)
        queenExchangeTimerJobs.remove(roomId)?.cancel()

        val state = GameStateManager.get(roomId) ?: return
        val target = state.players[targetId] ?: return

        // ガードチェック
        val hasGuard = state.handOf(targetId).any { it.cardType == CardType.GUARD }
        if (hasGuard) {
            broadcast(roomId, EventType.NOTIFY_GUARD_ACTIVATED, NotifyGuardActivatedPayload(targetId.toString()))
            val newState = GameStateManager.get(roomId) ?: return
            broadcastGameStateSync(roomId, newState)
        } else {
            swapApples(roomId, queenPlayerId, targetId)
            broadcast(roomId, EventType.NOTIFY_EXCHANGE_APPLE,
                NotifyExchangeApplePayload(queenPlayerId.toString(), targetId.toString()))
            val newState = GameStateManager.get(roomId) ?: return
            broadcastGameStateSync(roomId, newState)
        }

        GameStateManager.update(roomId) { it.copy(queenSpecialDone = true) }
        broadcast(roomId, EventType.PHASE_CHANGED, PhaseChangedPayload("ENDING_REVEAL", null))
        revealAllApplesAndProceed(roomId)
    }

    private suspend fun handleQueenExchangeTimeout(roomId: UUID, queenPlayerId: UUID) {
        if (pendingQueenExchange[roomId] != true) return
        pendingQueenExchange.remove(roomId)

        val state = GameStateManager.get(roomId) ?: return
        val aliveTargets = state.players.values.filter { it.isAlive && it.playerId != queenPlayerId }
        val randomTarget = aliveTargets.randomOrNull() ?: run {
            revealAllApplesAndProceed(roomId)
            return
        }

        broadcast(roomId, EventType.NOTIFY_TIMEOUT, NotifyTimeoutPayload(
            "QUEEN_EXCHANGE", queenPlayerId.toString(), "RANDOM_SELECTED"
        ))
        handleQueenExchangeResponse(roomId, queenPlayerId, randomTarget.playerId)
    }

    private suspend fun startEndingReveal(roomId: UUID) {
        // ホストが「エンディングリビールに進む」ボタンを押すまで待機（1分タイムアウト）
        pendingProceedToReveal[roomId] = true
        broadcast(roomId, EventType.WAITING_HOST_PROCEED, WaitingHostProceedPayload("ENDING_REVEAL"))
        proceedToRevealTimerJobs[roomId] = scope.launch {
            delay(60_000)
            if (pendingProceedToReveal[roomId] == true) {
                pendingProceedToReveal.remove(roomId)
                broadcast(roomId, EventType.NOTIFY_TIMEOUT, NotifyTimeoutPayload(
                    timeoutType = "HOST_PROCEED", playerId = "", autoAction = "ホストの操作が行われませんでした"
                ))
                executeEndingReveal(roomId)
            }
        }
    }

    suspend fun handleProceedToReveal(roomId: UUID, hostPlayerId: UUID) {
        if (pendingProceedToReveal[roomId] != true) return
        pendingProceedToReveal.remove(roomId)
        proceedToRevealTimerJobs.remove(roomId)?.cancel()
        executeEndingReveal(roomId)
    }

    private suspend fun executeEndingReveal(roomId: UUID) {
        val newState = GameStateManager.update(roomId) { it.copy(phase = GamePhase.ENDING_REVEAL) } ?: return
        broadcast(roomId, EventType.PHASE_CHANGED, PhaseChangedPayload("ENDING_REVEAL", null))

        // 一人ずつ役職公開 → リンゴ判定 → 死亡通知 を順番に行う
        val players = newState.players.values.sortedBy { newState.turnOrder.indexOf(it.playerId) }
        val totalPlayers = players.size

        players.forEachIndexed { index, player ->
            delay(4000) // 4秒間隔で一人ずつ公開

            val apple = newState.appleOf(player.playerId)
            val isPoisoned = apple?.isPoisoned ?: false
            val willDie = isPoisoned && player.isAlive

            // 役職とリンゴ結果を公開
            broadcast(roomId, EventType.ENDING_REVEAL_PLAYER, EndingRevealPlayerPayload(
                playerId = player.playerId.toString(),
                userName = player.userName,
                role = player.role.name,
                faction = player.faction.name,
                isPoisoned = isPoisoned,
                isAlive = !willDie && player.isAlive,
                revealIndex = index,
                totalPlayers = totalPlayers
            ))

            // 毒リンゴ保持者を死亡処理（白雪姫以外）
            if (willDie && player.role != Role.SNOW_WHITE) {
                GameStateManager.update(roomId) { s ->
                    s.copy(players = s.players + (player.playerId to player.copy(isAlive = false)))
                }
                broadcast(roomId, EventType.NOTIFY_PLAYER_DIED, NotifyPlayerDiedPayload(player.playerId.toString(), "POISON_APPLE"))
            } else if (willDie && player.role == Role.SNOW_WHITE) {
                // 白雪姫が毒リンゴで死亡 → 特殊演出後にゲーム終了
                GameStateManager.update(roomId) { s ->
                    s.copy(players = s.players + (player.playerId to player.copy(isAlive = false)))
                }
                broadcast(roomId, EventType.NOTIFY_PLAYER_DIED, NotifyPlayerDiedPayload(player.playerId.toString(), "POISON_APPLE"))
                broadcast(roomId, EventType.SNOW_WHITE_KILLED, SnowWhiteKilledPayload(
                    cause = "POISON_APPLE",
                    snowWhitePlayerId = player.playerId.toString()
                ))
                delay(5000)
                endGameQueenWins(roomId, "SNOW_WHITE_KILLED")
                return
            }
        }

        delay(5000) // 全員公開後に勝利演出前の間を取る
        val endState = GameStateManager.get(roomId) ?: return
        broadcastGameStateSync(roomId, endState)
        // ホストが「結果画面に進む」ボタンを押すまで待機（1分タイムアウト）
        pendingProceedToResult[roomId] = true
        broadcast(roomId, EventType.WAITING_HOST_PROCEED, WaitingHostProceedPayload("RESULT"))
        proceedToResultTimerJobs[roomId] = scope.launch {
            delay(60_000)
            if (pendingProceedToResult[roomId] == true) {
                pendingProceedToResult.remove(roomId)
                broadcast(roomId, EventType.NOTIFY_TIMEOUT, NotifyTimeoutPayload(
                    timeoutType = "HOST_PROCEED", playerId = "", autoAction = "ホストの操作が行われませんでした"
                ))
                val s = GameStateManager.get(roomId) ?: return@launch
                determineWinner(roomId, s)
            }
        }
    }

    suspend fun handleProceedToResult(roomId: UUID, hostPlayerId: UUID) {
        if (pendingProceedToResult[roomId] != true) return
        pendingProceedToResult.remove(roomId)
        proceedToResultTimerJobs.remove(roomId)?.cancel()
        val endState = GameStateManager.get(roomId) ?: return
        determineWinner(roomId, endState)
    }

    private suspend fun determineWinner(roomId: UUID, state: GameState) {
        val snowWhite = state.players.values.find { it.role == Role.SNOW_WHITE }
        val rose = state.players.values.find { it.role == Role.ROSE }

        val winFaction = when {
            snowWhite?.isAlive != true -> Faction.QUEEN_FACTION
            rose == null || !rose.isAlive -> Faction.SNOW_WHITE_FACTION
            else -> Faction.THIRD_FACTION
        }

        // 勝利演出を送信
        broadcast(roomId, EventType.VICTORY_ANNOUNCEMENT, VictoryAnnouncementPayload(
            winFaction = winFaction.name,
            snowWhiteAlive = snowWhite?.isAlive ?: false,
            roseAlive = rose?.isAlive
        ))
        delay(5000) // 5秒間の演出表示

        val resultState = GameStateManager.update(roomId) { it.copy(phase = GamePhase.FINISHED) } ?: return
        roomRepository.updateStatus(roomId, RoomStatus.FINISHED)

        val resultPlayers = resultState.players.values.map { p ->
            val apple = resultState.appleOf(p.playerId)
            val isWinner = p.faction == winFaction
            GameResultPlayer(
                playerId = p.playerId.toString(),
                userName = p.userName,
                role = p.role.name,
                faction = p.faction.name,
                isAlive = p.isAlive,
                isWinner = isWinner,
                apple = AppleInfo(apple?.appleId.toString(), apple?.isPoisoned ?: false)
            )
        }

        broadcast(roomId, EventType.GAME_RESULT, GameResultPayload(
            winFaction = winFaction.name,
            reason = "NORMAL",
            players = resultPlayers
        ))
        GameStateManager.remove(roomId)
    }

    private suspend fun forceEndGameBySnowWhiteDisconnect(roomId: UUID, snowWhiteId: UUID) {
        val state = GameStateManager.get(roomId) ?: return
        val player = state.players[snowWhiteId] ?: return
        GameStateManager.update(roomId) { s ->
            s.copy(players = s.players + (snowWhiteId to player.copy(isAlive = false)))
        }
        broadcast(roomId, EventType.NOTIFY_PLAYER_DIED, NotifyPlayerDiedPayload(snowWhiteId.toString(), "DISCONNECTED"))
        broadcast(roomId, EventType.SNOW_WHITE_KILLED, SnowWhiteKilledPayload(
            cause = "DISCONNECTED",
            snowWhitePlayerId = snowWhiteId.toString()
        ))
        roomRepository.updateStatus(roomId, RoomStatus.FINISHED)
        delay(3000)
        endGameQueenWins(roomId, "SNOW_WHITE_DISCONNECTED")
    }

    // ── 再ゲーム ─────────────────────────────────────────────────────

    suspend fun handleRematch(roomId: UUID, playerId: UUID) {
        val room = roomRepository.findById(roomId) ?: return
        if (room.hostPlayerId != playerId) return sendError(roomId, playerId, "NOT_HOST", "ホストのみが再ゲームを開始できます")

        playerRepository.deleteDisconnected(roomId)
        playerRepository.compactSeatOrders(roomId)
        roomRepository.updateStatus(roomId, RoomStatus.WAITING)

        broadcast(roomId, EventType.NOTIFY_REMATCH_STARTING, NotifyRematchStartingPayload())
    }

    // ── 切断処理 ─────────────────────────────────────────────────────

    suspend fun handlePlayerDisconnect(roomId: UUID, playerId: UUID) {
        val state = GameStateManager.get(roomId)
        val player = state?.players?.get(playerId) ?: return

        playerRepository.updateConnected(playerId, false)

        if (state.phase == GamePhase.FINISHED) return

        GameStateManager.update(roomId) { s ->
            s.copy(players = s.players + (playerId to player.copy(isConnected = false)))
        }

        val newState = GameStateManager.get(roomId) ?: return
        broadcastGameStateSync(roomId, newState)

        if (state.currentTurnPlayerId() == playerId) {
            // 手番中の切断 → ターンタイマーをキャンセルし、1分タイマー開始
            cancelTurnTimer(roomId)
            val job = scope.launch {
                delay(60_000)
                handleDisconnectTimeout(roomId, playerId)
            }
            disconnectTimerJobs[playerId] = job
        } else {
            // 手番外での切断 → 1分タイマー開始（白雪姫も含む全員対象）
            val job = scope.launch {
                delay(60_000)
                handleDisconnectTimeout(roomId, playerId)
            }
            disconnectTimerJobs[playerId] = job
        }
    }

    suspend fun handlePlayerReconnect(roomId: UUID, playerId: UUID) {
        disconnectTimerJobs.remove(playerId)?.cancel()
        playerRepository.updateConnected(playerId, true)

        val state = GameStateManager.update(roomId) { s ->
            val player = s.players[playerId] ?: return@update s
            s.copy(players = s.players + (playerId to player.copy(isConnected = true)))
        } ?: return

        broadcastGameStateSync(roomId, state)

        if (state.currentTurnPlayerId() == playerId) {
            broadcastTurnChanged(roomId, playerId)
            startTurnTimer(roomId, playerId)
        }
    }

    // ── ユーティリティ ────────────────────────────────────────────────

    private fun isCurrentTurn(state: GameState, playerId: UUID): Boolean =
        state.currentTurnPlayerId() == playerId

    private fun discardCard(roomId: UUID, cardId: UUID): GameCard? {
        var discarded: GameCard? = null
        GameStateManager.update(roomId) { s ->
            val card = s.cards[cardId] ?: return@update s
            discarded = card
            val updatedCard = card.copy(location = CardLocation.DISCARD, holderPlayerId = null)
            s.copy(
                cards = s.cards + (cardId to updatedCard),
                discardOrder = s.discardOrder + cardId
            )
        }
        return discarded
    }

    private fun moveCardToDiscard(roomId: UUID, cardId: UUID) {
        discardCard(roomId, cardId)
    }

    private suspend fun killPlayer(roomId: UUID, playerId: UUID, cause: String) {
        val state = GameStateManager.get(roomId) ?: return
        val player = state.players[playerId] ?: return
        GameStateManager.update(roomId) { s ->
            s.copy(players = s.players + (playerId to player.copy(isAlive = false)))
        }
        broadcast(roomId, EventType.NOTIFY_PLAYER_DIED, NotifyPlayerDiedPayload(playerId.toString(), cause))

        // 白雪姫が死亡した場合 → 専用演出を送信してからゲーム終了
        if (player.role == Role.SNOW_WHITE && cause != "POISON_APPLE") {
            broadcast(roomId, EventType.SNOW_WHITE_KILLED, SnowWhiteKilledPayload(
                cause = cause,
                snowWhitePlayerId = playerId.toString()
            ))
            delay(3000)
            endGameQueenWins(roomId, "SNOW_WHITE_KILLED")
        }
    }

    private suspend fun endGameQueenWins(roomId: UUID, reason: String) {
        val state = GameStateManager.get(roomId) ?: return
        cancelTurnTimer(roomId)
        val resultPlayers = state.players.values.map { p ->
            val apple = state.appleOf(p.playerId)
            GameResultPlayer(
                playerId = p.playerId.toString(), userName = p.userName,
                role = p.role.name, faction = p.faction.name,
                isAlive = p.isAlive, isWinner = p.faction == Faction.QUEEN_FACTION,
                apple = AppleInfo(apple?.appleId.toString(), apple?.isPoisoned ?: false)
            )
        }
        broadcast(roomId, EventType.GAME_RESULT, GameResultPayload(
            winFaction = Faction.QUEEN_FACTION.name,
            reason = reason,
            players = resultPlayers
        ))
        GameStateManager.remove(roomId)
    }

    private fun swapApples(roomId: UUID, playerA: UUID, playerB: UUID) {
        GameStateManager.update(roomId) { s ->
            val updatedApples = s.apples.map { apple ->
                when (apple.currentHolderPlayerId) {
                    playerA -> apple.copy(currentHolderPlayerId = playerB)
                    playerB -> apple.copy(currentHolderPlayerId = playerA)
                    else -> apple
                }
            }
            s.copy(apples = updatedApples)
        }
    }

    private suspend fun notifyBlackIfPresent(roomId: UUID) {
        val state = GameStateManager.get(roomId) ?: return
        val blackPlayer = state.players.values.find { it.role == Role.BLACK && it.isAlive } ?: return
        val poisonApples = state.apples.filter { it.isPoisoned }
            .map { PoisonAppleLocation(it.appleId.toString(), it.currentHolderPlayerId.toString()) }
        sendTo(roomId, blackPlayer.playerId, EventType.BLACK_APPLE_UPDATE, BlackAppleUpdatePayload(poisonApples))
    }

    // ── ブロードキャスト ──────────────────────────────────────────────

    private suspend fun broadcastGameStarted(roomId: UUID, state: GameState) {
        val payload = GameStartedPayload(
            gameId = state.gameId.toString(),
            players = state.players.values.map { it.toSummary() },
            firstTurnPlayerId = state.currentTurnPlayerId().toString()
        )
        broadcast(roomId, EventType.GAME_STARTED, payload)
    }

    private suspend fun sendAllInitialInfo(roomId: UUID, state: GameState) {
        val blackPlayer = state.players.values.find { it.role == Role.BLACK }
        val snowWhiteId = state.players.values.find { it.role == Role.SNOW_WHITE }?.playerId

        state.players.values.forEach { player ->
            val myApple = state.appleOf(player.playerId) ?: return@forEach
            val myHand = state.handOf(player.playerId).map { CardInfo(it.cardId.toString(), it.cardType.name) }
            val payload = YourInitialInfoPayload(
                role = player.role.name,
                faction = player.faction.name,
                myApple = AppleInfo(myApple.appleId.toString(), myApple.isPoisoned),
                myHand = myHand,
                snowWhitePlayerId = if (player.role == Role.GREEN) snowWhiteId?.toString() else null,
                poisonAppleHolderIds = if (player.role == Role.BLACK)
                    state.apples.filter { it.isPoisoned }.map { it.currentHolderPlayerId.toString() } else null
            )
            sendTo(roomId, player.playerId, EventType.YOUR_INITIAL_INFO, payload)
        }
    }

    private suspend fun broadcastGameStateSync(roomId: UUID, state: GameState) {
        state.players.values.forEach { player ->
            sendGameStateSyncTo(roomId, player.playerId, state)
        }
    }

    private suspend fun sendGameStateSyncTo(roomId: UUID, playerId: UUID, state: GameState) {
        val myHand = state.handOf(playerId).map { CardInfo(it.cardId.toString(), it.cardType.name) }
        val isBlack = state.players[playerId]?.role == Role.BLACK
        val apples = state.apples.map { apple ->
            val knownPoison = isBlack || apple.isPubliclyRevealed ||
                    apple.privatelyKnownBy.contains(playerId)
            AppleSummary(
                appleId = apple.appleId.toString(),
                currentHolderPlayerId = apple.currentHolderPlayerId.toString(),
                isPoisoned = if (knownPoison) apple.isPoisoned else null,
                isPubliclyRevealed = apple.isPubliclyRevealed
            )
        }
        val discardPile = state.discardOrder.mapNotNull { id ->
            state.cards[id]?.let { CardInfo(it.cardId.toString(), it.cardType.name) }
        }
        val payload = GameStateSyncPayload(
            phase = state.phase.name,
            currentTurnPlayerId = if (state.phase in listOf(GamePhase.STORY, GamePhase.LAST_TURN))
                state.currentTurnPlayerId().toString() else null,
            deckRemainingCount = state.deckOrder.size,
            discardPile = discardPile,
            players = state.players.values.map { it.toSummary(visibleTo = playerId) },
            apples = apples,
            myHand = myHand
        )
        sendTo(roomId, playerId, EventType.GAME_STATE_SYNC, payload)
    }

    private suspend fun broadcastGameStateSyncExcept(roomId: UUID, excludeId: UUID, state: GameState) {
        state.players.values.filter { it.playerId != excludeId }.forEach { player ->
            sendGameStateSyncTo(roomId, player.playerId, state)
        }
    }

    private suspend fun broadcastTurnChanged(roomId: UUID, playerId: UUID) {
        broadcast(roomId, EventType.TURN_CHANGED, TurnChangedPayload(playerId.toString(), 180))
    }

    // ── 送信ヘルパー ─────────────────────────────────────────────────

    private suspend inline fun <reified T> broadcast(roomId: UUID, type: String, payload: T) {
        val msg = WsMessage(type, Json.encodeToJsonElement(payload))
        connectionManager.broadcast(roomId, msg)
    }

    private suspend inline fun <reified T> sendTo(roomId: UUID, playerId: UUID, type: String, payload: T) {
        val msg = WsMessage(type, Json.encodeToJsonElement(payload))
        connectionManager.sendTo(roomId, playerId, msg)
    }

    private suspend fun sendError(roomId: UUID, playerId: UUID, code: String, message: String) {
        sendTo(roomId, playerId, EventType.ERROR, ErrorPayload(code, message))
    }

    // ── 拡張関数 ─────────────────────────────────────────────────────

    private fun GamePlayer.toSummary(visibleTo: UUID? = null): PlayerSummary {
        val showRole = isRoleRevealed || visibleTo == this.playerId
        return PlayerSummary(
            playerId = playerId.toString(),
            userName = userName,
            seatOrder = seatOrder,
            isAlive = isAlive,
            isConnected = isConnected,
            isRoleRevealed = isRoleRevealed,
            role = if (showRole) role.name else null,
            isProtected = isProtected,
            skipNextTurn = skipNextTurn,
            applePreferenceAnswer = applePreferenceAnswer,
            mushroomPreferenceAnswer = mushroomPreferenceAnswer
        )
    }

    private fun Role.faction(): Faction = when (this) {
        Role.SNOW_WHITE, Role.GREEN, Role.BROWN, Role.GRAY, Role.LIGHT -> Faction.SNOW_WHITE_FACTION
        Role.QUEEN, Role.BLACK, Role.NAVY -> Faction.QUEEN_FACTION
        Role.ROSE -> Faction.THIRD_FACTION
    }
}
