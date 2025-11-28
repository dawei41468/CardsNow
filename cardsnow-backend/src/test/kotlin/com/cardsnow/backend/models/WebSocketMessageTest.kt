package com.cardsnow.backend.models

import com.cardsnow.backend.plugins.json
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebSocketMessageTest {

    @Test
    fun error_roundTrip_and_discriminator() {
        val msg = WebSocketMessage.Error("boom", ErrorType.TRANSIENT)
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val root = json.parseToJsonElement(s).jsonObject
        assertEquals("error", root["messageType"]!!.jsonPrimitive.content)
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
    fun gameStateUpdate_roundTrip() {
        val gameState = GameState(
            deck = emptyList(),
            table = emptyList(),
            discardPile = emptyList(),
            lastPlayed = LastPlayed("Alice", listOf()),
            lastDiscarded = LastPlayed("Bob", listOf()),
            version = 1
        )
        val players = mapOf(
            "Alice" to Player(name = "Alice", isHost = true, isConnected = true, hand = emptyList()),
            "Bob" to Player(name = "Bob", isHost = false, isConnected = true, hand = emptyList())
        )
        val msg = WebSocketMessage.GameStateUpdate(roomCode = "1234", gameState = gameState, players = players)
        val s = json.encodeToString(WebSocketMessage.serializer(), msg)
        val back = json.decodeFromString<WebSocketMessage>(s) as WebSocketMessage.GameStateUpdate
        assertEquals(msg, back)
    }

    @Test
    fun roomCreated_and_sessionCreated_roundTrip() {
        val roomCreated = WebSocketMessage.RoomCreated(roomCode = "1234", players = listOf("Alice", "Bob"))
        val s1 = json.encodeToString(WebSocketMessage.serializer(), roomCreated)
        val back1 = json.decodeFromString<WebSocketMessage>(s1)
        assertEquals(roomCreated, back1)

        val sessionCreated = WebSocketMessage.SessionCreated(sessionId = "abc123", playerName = "Alice", roomCode = "1234")
        val s2 = json.encodeToString(WebSocketMessage.serializer(), sessionCreated)
        val back2 = json.decodeFromString<WebSocketMessage>(s2)
        assertEquals(sessionCreated, back2)
    }
}
