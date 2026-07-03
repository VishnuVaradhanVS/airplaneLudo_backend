package com.example.backend
import com.example.shared.data.Room
import java.util.Collections

data class ActiveRoom(
    var roomData: Room,
    val sessions: MutableSet<LudoSession> = Collections.synchronizedSet(LinkedHashSet())
)
