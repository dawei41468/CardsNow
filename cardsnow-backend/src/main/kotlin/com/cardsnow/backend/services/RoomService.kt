package com.cardsnow.backend.services

import com.cardsnow.backend.models.*
import java.util.concurrent.ConcurrentHashMap

class RoomService {
    private val rooms = ConcurrentHashMap<String, Room>()
    
    fun createRoom(settings: RoomSettings, hostName: String): String {
        val code = generateRoomCode()
        val room = Room(code, settings, hostName)
        room.addPlayer(hostName)
        rooms[code] = room
        println("Room created: $code by $hostName with ${settings.numDecks} decks")
        return code
    }
    
    fun joinRoom(code: String, playerName: String): Boolean {
        val room = rooms[code] ?: return false
        val success = room.addPlayer(playerName)
        if (success) {
            println("Player $playerName joined room $code")
        }
        return success
    }
    
    fun getRoom(code: String): Room? = rooms[code]

    fun updateRoom(code: String, room: Room) {
        rooms[code] = room
    }

    fun removePlayerFromRoom(code: String, playerName: String) {
        val room = rooms[code] ?: return
        room.removePlayer(playerName)
        println("Player $playerName removed from room $code")
        
        // Remove room if empty
        if (room.players.isEmpty()) {
            rooms.remove(code)
            println("Room $code deleted (empty)")
        }
    }
    
    fun cleanupOldRooms(maxAgeMinutes: Int = 30) {
        val now = System.currentTimeMillis()
        val threshold = now - (maxAgeMinutes * 60 * 1000)
        
        val roomsToRemove = rooms.filter { (_, room) ->
            room.lastActive < threshold
        }.keys
        
        roomsToRemove.forEach { code ->
            rooms.remove(code)
            println("Deleted stale room: $code")
        }
    }
    
    fun getAllRooms(): Map<String, Room> = rooms.toMap()
    
    private fun generateRoomCode(): String {
        var code: String
        do {
            code = (1000..9999).random().toString()
        } while (rooms.containsKey(code))
        return code
    }
}