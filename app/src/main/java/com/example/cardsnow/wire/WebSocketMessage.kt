package com.example.cardsnow.wire

import com.example.cardsnow.Card
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ErrorType {
    @SerialName("TRANSIENT")
    TRANSIENT,
    @SerialName("CRITICAL")
    CRITICAL
}

@Serializable
enum class SortType {
    RANK,
    SUIT
}

@Serializable
enum class ErrorCode {
    RATE_LIMITED,
    PAYLOAD_TOO_LARGE,
    TIMEOUT,
    INVALID_FORMAT,
    VALIDATION,
    NOT_FOUND,
    AUTHZ,
    CONFLICT,
    UNKNOWN
}

@Serializable
sealed class WebSocketMessage {
    @Serializable
    @SerialName("join_room")
    data class JoinRoom(val roomCode: String, val playerName: String, val opId: String? = null) : WebSocketMessage()

    @Serializable
    @SerialName("create_room")
    data class CreateRoom(val settings: RoomSettings, val hostName: String, val opId: String? = null) : WebSocketMessage()

    @Serializable
    @SerialName("start_game")
    data class StartGame(val roomCode: String, val opId: String? = null) : WebSocketMessage()

    @Serializable
    @SerialName("play_cards")
    data class PlayCards(val roomCode: String, val playerName: String, val cardIds: List<String>, val opId: String? = null) : WebSocketMessage()

    @Serializable
    @SerialName("discard_cards")
    data class DiscardCards(val roomCode: String, val playerName: String, val cardIds: List<String>, val opId: String? = null) : WebSocketMessage()

    @Serializable
    @SerialName("draw_card")
    data class DrawCard(val roomCode: String, val playerName: String, val opId: String? = null) : WebSocketMessage()

    @Serializable
    @SerialName("draw_from_discard")
    data class DrawFromDiscard(val roomCode: String, val playerName: String, val opId: String? = null) : WebSocketMessage()

    @Serializable
    @SerialName("shuffle_deck")
    data class ShuffleDeck(val roomCode: String, val playerName: String, val opId: String? = null) : WebSocketMessage()

    @Serializable
    @SerialName("deal_cards")
    data class DealCards(val roomCode: String, val playerName: String, val count: Int, val opId: String? = null) : WebSocketMessage()

    @Serializable
    @SerialName("move_cards")
    data class MoveCards(val roomCode: String, val fromPlayer: String, val toPlayer: String, val cardIds: List<String>, val opId: String? = null) : WebSocketMessage()

    @Serializable
    @SerialName("recall_last_pile")
    data class RecallLastPile(val roomCode: String, val playerName: String, val opId: String? = null) : WebSocketMessage()

    @Serializable
    @SerialName("recall_last_discard")
    data class RecallLastDiscard(val roomCode: String, val playerName: String, val opId: String? = null) : WebSocketMessage()

    @Serializable
    @SerialName("sort_hand")
    data class SortHand(val roomCode: String, val playerName: String, val sortBy: SortType, val opId: String? = null) : WebSocketMessage()

    @Serializable
    @SerialName("reorder_hand")
    data class ReorderHand(val roomCode: String, val playerName: String, val cardIds: List<String>, val opId: String? = null) : WebSocketMessage()

    @Serializable
    @SerialName("game_state_update")
    data class GameStateUpdate(
        val roomCode: String,
        val gameState: GameState,
        val players: Map<String, Player>
    ) : WebSocketMessage()

    @Serializable
    @SerialName("error")
    data class Error(val message: String, val type: ErrorType = ErrorType.TRANSIENT, val code: ErrorCode? = null, val opId: String? = null) : WebSocketMessage()

    @Serializable
    @SerialName("success")
    data class Success(val message: String, val opId: String? = null) : WebSocketMessage()

    @Serializable
    @SerialName("player_joined")
    data class PlayerJoined(val roomCode: String, val playerName: String, val players: List<String>) : WebSocketMessage()

    @Serializable
    @SerialName("player_left")
    data class PlayerLeft(val roomCode: String, val playerName: String, val newHost: String?) : WebSocketMessage()

    @Serializable
    @SerialName("room_created")
    data class RoomCreated(val roomCode: String, val players: List<String>) : WebSocketMessage()

    @Serializable
    @SerialName("session_created")
    data class SessionCreated(val sessionId: String, val playerName: String, val roomCode: String) : WebSocketMessage()

    @Serializable
    @SerialName("restart_game")
    data class RestartGame(val roomCode: String, val playerName: String, val opId: String? = null) : WebSocketMessage()

    @Serializable
    @SerialName("reconnect")
    data class Reconnect(val roomCode: String, val playerName: String, val sessionId: String, val opId: String? = null) : WebSocketMessage()
}

@Serializable
data class Player(
    val name: String,
    val isHost: Boolean = false,
    val isConnected: Boolean = true,
    val hand: List<Card> = emptyList()
)

@Serializable
data class LastPlayed(
    val player: String = "",
    val cardIds: List<String> = emptyList()
)

@Serializable
data class GameState(
    val deck: List<Card> = emptyList(),
    val table: List<List<Card>> = emptyList(),
    val discardPile: List<Card> = emptyList(),
    val lastPlayed: LastPlayed = LastPlayed(),
    val lastDiscarded: LastPlayed = LastPlayed(),
    val version: Long = 0
)

@Serializable
data class RoomSettings(
    val numDecks: Int,
    val includeJokers: Boolean,
    val dealCount: Int
)
