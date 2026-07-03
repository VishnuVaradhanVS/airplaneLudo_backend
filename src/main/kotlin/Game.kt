package com.example.shared.data

import kotlinx.serialization.Serializable

@Serializable
data class Game(
    var currentPlayer: Player?,
    var redTokens: List<Token>,
    var blueTokens: List<Token>,
    var redHomeEnabled: Boolean,
    var blueHomeEnabled: Boolean,
    var redBaseCount: Int,
    var blueBaseCount: Int,
    var redHomeCount: Int,
    var blueHomeCount: Int,
    var diceValue: Dice,
    var diceDenomination: MutableMap<Int, Int>,
    var prevRedPlayer: Player?,
    var prevBluePlayer: Player?
    ) {
}