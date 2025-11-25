package com.example.cardsnow

import kotlinx.serialization.Serializable

@Serializable
data class RoomSettings(
    val numDecks: Int = 1,
    val includeJokers: Boolean = false,
    val dealCount: Int = 0
)