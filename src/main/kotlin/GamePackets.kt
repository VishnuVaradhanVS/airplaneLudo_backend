package com.example.shared.data

import kotlinx.serialization.Serializable

@Serializable
class GamePackets(
    var lobbyAction: LobbyAction? = null,
    var gameAction: GameAction? = null,
    var player: Player? = null,
    var room: Room? = null,
    var game: Game? = null,
    var selectedValue: Int? = null,
    var moveTokenId: Int? = null
) {
    override fun toString(): String {
        return "GamePackets(gameAction=$lobbyAction, player=$player, room=$room)"
    }
}