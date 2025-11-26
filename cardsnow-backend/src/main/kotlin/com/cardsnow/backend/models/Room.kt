package com.cardsnow.backend.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Serializable
data class Room(
    val code: String,
    val settings: RoomSettings,
    val hostName: String,
    val players: MutableMap<String, Player> = ConcurrentHashMap(),
    val gameState: GameState = GameState(),
    var lastActive: Long = System.currentTimeMillis(),
    var state: RoomState = RoomState.WAITING
) {
    @kotlinx.serialization.Transient
    private val lock = ReentrantLock()

    fun <T> atomicUpdate(block: () -> T): T {
        return lock.withLock(block)
    }
    fun addPlayer(playerName: String): Boolean {
        if (players.size >= 4 || players.containsKey(playerName)) {
            return false
        }
        
        val isHost = players.isEmpty()
        players[playerName] = Player(playerName, isHost)
        lastActive = System.currentTimeMillis()
        return true
    }
    
    fun removePlayer(playerName: String) {
        players.remove(playerName)
        lastActive = System.currentTimeMillis()
        
        // Reassign host if needed
        if (players.isNotEmpty() && !players.values.any { it.isHost }) {
            val newHost = players.values.first()
            players[newHost.name] = newHost.copy(isHost = true)
        }
    }
    
    fun updateLastActive() {
        lastActive = System.currentTimeMillis()
    }
}

@Serializable
enum class RoomState {
    WAITING,
    STARTED,
    ENDED
}

@Serializable
data class GameState(
    val deck: List<Card> = emptyList(),
    val table: List<List<Card>> = emptyList(),
    val discardPile: List<Card> = emptyList(),
    val lastPlayed: LastPlayed = LastPlayed(),
    val version: Long = 0
)

@Serializable
data class LastPlayed(
    val player: String = "",
    val cardIds: List<String> = emptyList()
)
