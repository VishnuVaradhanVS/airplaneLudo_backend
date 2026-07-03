package com.example.shared.data

import kotlinx.serialization.Serializable

@Serializable
data class Player(
    var id: Long,
    var name: String,
    var team: Team,
    var moveTokens: Boolean,
    var turnsLeft: Int,
    var playerGameState: PlayerGameState
) {
    fun swapTeam() {
        if (this.team == Team.RED) {
            this.team = Team.BLUE
        } else {
            this.team = Team.RED
        }
    }

    fun enableMoveTokens() {
        this.moveTokens = true
    }

    fun handleTurn(isDoubles: Boolean) {
        if (!isDoubles) {
            this.turnsLeft -= 1
        }
    }

    fun killToken() {
        this.turnsLeft += 1
    }

    fun switchPlayerState(playerGameState: PlayerGameState) {
        this.playerGameState = playerGameState
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Player

        if (id != other.id) return false
        if (name != other.name) return false
        if (team != other.team) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + name.hashCode()
        result = 31 * result + team.hashCode()
        return result.toInt()
    }

}