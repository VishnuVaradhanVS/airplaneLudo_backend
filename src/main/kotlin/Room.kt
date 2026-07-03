package com.example.shared.data

import kotlinx.serialization.Serializable

@Serializable
data class Room(
    val id: Int,
    val hostId: Long,
    val players: List<Player> = emptyList(),
    var baseTokenCount: Int,
    var game: Game? = null,
    var inGame: Boolean
) {
    val redTeam: List<Player> get() = players.filter { it.team == Team.RED }
    val blueTeam: List<Player> get() = players.filter { it.team == Team.BLUE }

}