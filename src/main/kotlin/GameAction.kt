package com.example.shared.data

import kotlinx.serialization.Serializable

@Serializable
enum class GameAction {
    StartGame, EndGame, RollDice, MoveToken, SpawnToken, GameUpdate, AnimateTokenMovement, LeaveGame
}