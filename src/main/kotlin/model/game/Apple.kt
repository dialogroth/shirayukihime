package model.game

import java.util.UUID

data class Apple(
    val appleId: UUID,
    val isPoisoned: Boolean,
    val currentHolderPlayerId: UUID,
    val isPubliclyRevealed: Boolean = false,
    val privatelyKnownBy: Set<UUID> = emptySet()
)
