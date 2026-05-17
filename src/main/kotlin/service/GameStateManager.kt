package service

import model.game.GameState
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object GameStateManager {

    private val states = ConcurrentHashMap<UUID, GameState>()

    fun set(roomId: UUID, state: GameState) {
        states[roomId] = state
    }

    fun get(roomId: UUID): GameState? = states[roomId]

    fun update(roomId: UUID, transform: (GameState) -> GameState): GameState? {
        val current = states[roomId] ?: return null
        val updated = transform(current)
        states[roomId] = updated
        return updated
    }

    fun remove(roomId: UUID) {
        states.remove(roomId)
    }

    fun exists(roomId: UUID): Boolean = states.containsKey(roomId)
}
