package com.cardsnow.backend.models

import kotlinx.serialization.Serializable

@Serializable
data class Player(
    val name: String,
    val isHost: Boolean = false,
    val isConnected: Boolean = true,
    val hand: List<Card> = emptyList()
)