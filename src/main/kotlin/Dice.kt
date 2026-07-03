package com.example.shared.data

import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
data class Dice(var d1: Int, var d2: Int) {
    var sum = 0
    val doubles = listOf(1, 5, 6, 12)
    fun getRandomValues() {
        this.d1 = Random.nextInt(4)
        this.d2 = Random.nextInt(4)
        this.sum = d1 + d2
        if (sum == 0) {
            sum = 12
        }
    }
}
