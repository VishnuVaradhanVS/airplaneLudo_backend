package com.example.shared.data

import kotlinx.serialization.Serializable


@Serializable
enum class PlayerGameState {
    Waiting, Rolling, Moving
}