package com.example.cardsnow.wire

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebSocketMessageTest {

    private val json = Json {
        classDiscriminator = "messageType"
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun error_roundTrip_and_discriminator() {
        val msg = WebSocketMessage.Error("boom", ErrorType.TRANSIENT)
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun error_with_code_roundTrip() {
        val msg = WebSocketMessage.Error("Too big", ErrorType.TRANSIENT, ErrorCode.PAYLOAD_TOO_LARGE)
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun success_roundTrip() {
        val msg = WebSocketMessage.Success(message = "ok")
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun playerJoined_roundTrip() {
        val msg = WebSocketMessage.PlayerJoined(roomCode = "1234", playerName = "Alice", players = listOf("Alice", "Bob"))
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun playerLeft_roundTrip_withNewHost() {
        val msg = WebSocketMessage.PlayerLeft(roomCode = "1234", playerName = "Bob", newHost = "Alice")
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun playerLeft_roundTrip_nullNewHost() {
        val msg = WebSocketMessage.PlayerLeft(roomCode = "1234", playerName = "Bob", newHost = null)
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun joinRoom_roundTrip() {
        val msg = WebSocketMessage.JoinRoom(roomCode = "1234", playerName = "Alice")
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun createRoom_roundTrip() {
        val settings = RoomSettings(numDecks = 2, includeJokers = true, dealCount = 0)
        val msg = WebSocketMessage.CreateRoom(settings = settings, hostName = "Alice")
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun startGame_roundTrip() {
        val msg = WebSocketMessage.StartGame(roomCode = "1234")
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun playCards_roundTrip() {
        val msg = WebSocketMessage.PlayCards(roomCode = "1234", playerName = "Alice", cardIds = listOf("c1", "c2"))
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun discardCards_roundTrip() {
        val msg = WebSocketMessage.DiscardCards(roomCode = "1234", playerName = "Alice", cardIds = listOf("c3"))
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun drawCard_roundTrip() {
        val msg = WebSocketMessage.DrawCard(roomCode = "1234", playerName = "Alice")
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun drawFromDiscard_roundTrip() {
        val msg = WebSocketMessage.DrawFromDiscard(roomCode = "1234", playerName = "Alice")
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun shuffleDeck_roundTrip() {
        val msg = WebSocketMessage.ShuffleDeck(roomCode = "1234", playerName = "Alice")
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun dealCards_roundTrip() {
        val msg = WebSocketMessage.DealCards(roomCode = "1234", playerName = "Alice", count = 3)
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun moveCards_roundTrip() {
        val msg = WebSocketMessage.MoveCards(roomCode = "1234", fromPlayer = "Alice", toPlayer = "Bob", cardIds = listOf("c1"))
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun recallLastPile_roundTrip() {
        val msg = WebSocketMessage.RecallLastPile(roomCode = "1234", playerName = "Alice")
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun recallLastDiscard_roundTrip() {
        val msg = WebSocketMessage.RecallLastDiscard(roomCode = "1234", playerName = "Alice")
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun sortHand_roundTrip() {
        val msg = WebSocketMessage.SortHand(roomCode = "1234", playerName = "Alice", sortBy = SortType.RANK)
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun reorderHand_roundTrip() {
        val msg = WebSocketMessage.ReorderHand(roomCode = "1234", playerName = "Alice", cardIds = listOf("c1", "c2", "c3"))
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun restartGame_roundTrip() {
        val msg = WebSocketMessage.RestartGame(roomCode = "1234", playerName = "Alice")
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun reconnect_roundTrip() {
        val msg = WebSocketMessage.Reconnect(roomCode = "1234", playerName = "Alice", sessionId = "sess-1")
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun reconnect_requires_sessionId() {
        val obj = buildJsonObject {
            put("messageType", "reconnect")
            put("roomCode", "1234")
            put("playerName", "Alice")
        }
        val s = obj.toString()
        try {
            json.decodeFromString<WebSocketMessage>(s)
            fail("Expected SerializationException for missing sessionId")
        } catch (e: SerializationException) {
            assertTrue(e.message?.contains("sessionId") == true)
        }
    }

    @Test
    fun roomCreated_roundTrip() {
        val msg = WebSocketMessage.RoomCreated(roomCode = "1234", players = listOf("Alice"))
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun sessionCreated_roundTrip() {
        val msg = WebSocketMessage.SessionCreated(sessionId = "sess-1", playerName = "Alice", roomCode = "1234")
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }

    @Test
    fun gameStateUpdate_roundTrip() {
        val card = com.example.cardsnow.Card(suit = "Spades", rank = "Ace", resourceId = 0, id = "c1")
        val gs = GameState(
            deck = listOf(card),
            table = listOf(listOf(card)),
            discardPile = listOf(card),
            lastPlayed = LastPlayed(player = "Alice", cardIds = listOf("c1")),
            lastDiscarded = LastPlayed(player = "", cardIds = emptyList()),
            version = 1
        )
        val players = mapOf(
            "Alice" to Player(
                name = "Alice",
                isHost = true,
                isConnected = true,
                hand = listOf(card)
            )
        )
        val msg = WebSocketMessage.GameStateUpdate(roomCode = "1234", gameState = gs, players = players)
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s)
        assertEquals(msg, back)
    }
}
