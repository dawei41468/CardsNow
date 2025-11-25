package com.cardsnow.backend.models

import kotlinx.serialization.Serializable

@Serializable
data class Card(
    val suit: String,
    val rank: String,
    val resourceId: Int,
    val id: String
)