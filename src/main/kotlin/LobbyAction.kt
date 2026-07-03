package com.example.shared.data

import kotlinx.serialization.Serializable

@Serializable
enum class LobbyAction {
    PlayerAck, RoomUpdate, Kick, SwapTeam, ChangeTokenCount, RoomInGame
};