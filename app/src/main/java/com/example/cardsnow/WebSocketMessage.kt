package com.example.cardsnow

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class WebSocketMessage {
    @Serializable
    @SerialName("join_room")
    data class JoinRoom(val roomCode: String, val playerName: String) : WebSocketMessage()

    @Serializable
    @SerialName("create_room")
    data class CreateRoom(val settings: RoomSettings, val hostName: String) : WebSocketMessage()

    @Serializable
    @SerialName("start_game")
    data class StartGame(val roomCode: String) : WebSocketMessage()

    @Serializable
    @SerialName("play_cards")
    data class PlayCards(val roomCode: String, val playerName: String, val cardIds: List<String>) : WebSocketMessage()

    @Serializable
    @SerialName("discard_cards")
    data class DiscardCards(val roomCode: String, val playerName: String, val cardIds: List<String>) : WebSocketMessage()

    @Serializable
    @SerialName("draw_card")
    data class DrawCard(val roomCode: String, val playerName: String) : WebSocketMessage()

    @Serializable
    @SerialName("draw_from_discard")
    data class DrawFromDiscard(val roomCode: String, val playerName: String) : WebSocketMessage()

    @Serializable
    @SerialName("shuffle_deck")
    data class ShuffleDeck(val roomCode: String, val playerName: String) : WebSocketMessage()

    @Serializable
    @SerialName("deal_cards")
    data class DealCards(val roomCode: String, val playerName: String, val count: Int) : WebSocketMessage()

    @Serializable
    @SerialName("move_cards")
    data class MoveCards(val roomCode: String, val fromPlayer: String, val toPlayer: String, val cardIds: List<String>) : WebSocketMessage()

    @Serializable
    @SerialName("recall_last_pile")
    data class RecallLastPile(val roomCode: String, val playerName: String) : WebSocketMessage()

    @Serializable
    @SerialName("reorder_hand")
    data class ReorderHand(val roomCode: String, val playerName: String, val cardIds: List<String>) : WebSocketMessage()

    @Serializable
    @SerialName("restart_game")
    data class RestartGame(val roomCode: String, val playerName: String) : WebSocketMessage()
    
    @SerialName("room_created")
    data class RoomCreated(val roomCode: String, val players: List<String>) : WebSocketMessage()
}