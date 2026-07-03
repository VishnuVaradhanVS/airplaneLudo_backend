package com.example.backend

import com.example.shared.data.Player
import io.ktor.websocket.DefaultWebSocketSession

data class LudoSession(
    val player: Player,
    val session: DefaultWebSocketSession
)