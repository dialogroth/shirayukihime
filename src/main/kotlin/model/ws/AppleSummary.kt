package model.ws

import kotlinx.serialization.Serializable

@Serializable
data class AppleSummary(
    val appleId: String,
    val currentHolderPlayerId: String,
    val isPoisoned: Boolean? = null,
    val isPubliclyRevealed: Boolean
)
