package com.example.shared.data

import kotlinx.serialization.Serializable

@Serializable
data class Token(val id: Int, val team: Team, var position: Int, var cords: GridPathCords) {
    fun moveToken(steps: Int, homeEnabled: Boolean) {
        if (homeEnabled || this.position + steps <= 59) {
            this.position += steps
        } else {
            val prevPos = this.position
            this.position = 12
            this.position += (steps - (60 - prevPos))
        }
        if (team == Team.RED) {
            this.cords = redPath[this.position]
        } else {
            this.cords = bluePath[this.position]
        }
    }

    fun killToken() {
        this.position = -1
        this.cords = GridPathCords(-1, -1)
    }

    fun decrementPosition() {
        this.position -= 1
        if (this.position == -1) {
            this.cords = GridPathCords(-1, -1)
            return
        }
        if (team == Team.RED) {
            this.cords = redPath[position]
        } else {
            this.cords = bluePath[position]
        }
    }

    fun incrementPosition() {
        this.position += 1
        if (this.position == redPath.size) {
            this.position = redPath.size - 1
        }
        if (team == Team.RED) {
            this.cords = redPath[position]
        } else {
            this.cords = bluePath[position]
        }
    }
}