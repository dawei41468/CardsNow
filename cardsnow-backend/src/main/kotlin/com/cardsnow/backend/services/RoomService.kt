package com.cardsnow.backend.services

import com.cardsnow.backend.models.*
import com.cardsnow.backend.config.ServerConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class RoomService {
    private val rooms = ConcurrentHashMap<String, Room>()
    private val roomLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val logger = LoggerFactory.getLogger(RoomService::class.java)
    
    fun createRoom(settings: RoomSettings, hostName: String): String {
        val code = generateRoomCode()
        val room = Room(code, settings, hostName)
        room.addPlayer(hostName)
        rooms[code] = room
        logger.info("Room created: {} by {} with {} decks", code, hostName, settings.numDecks)
        return code
    }
    
    fun joinRoom(code: String, playerName: String): Boolean {
        val room = rooms[code] ?: return false
        val success = room.addPlayer(playerName)
        if (success) {
            logger.info("Player {} joined room {}", playerName, code)
        }
        return success
    }
    
    fun getRoom(code: String): Room? = rooms[code]

    fun updateRoom(code: String, room: Room) {
        rooms[code] = room
    }

    fun <T> executeRoomOperation(roomCode: String, operation: () -> T): T {
        val lock = roomLocks.computeIfAbsent(roomCode) { ReentrantLock() }
        if (lock.tryLock(ServerConfig.LOCK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            try {
                return operation()
            } finally {
                lock.unlock()
            }
        } else {
            val seconds = ServerConfig.LOCK_TIMEOUT_MS / 1000
            throw IllegalStateException("Could not acquire lock for room $roomCode within ${seconds} seconds")
        }
    }

    fun removePlayerFromRoom(code: String, playerName: String) {
        val room = rooms[code] ?: return
        room.removePlayer(playerName)
        logger.info("Player {} removed from room {}", playerName, code)
        
        // Remove room if empty
        if (room.players.isEmpty()) {
            rooms.remove(code)
            logger.info("Room {} deleted (empty)", code)
        }
    }
    
    fun cleanupOldRooms(maxAgeMinutes: Int = ServerConfig.OLD_ROOM_MAX_AGE_MINUTES) {
        val now = System.currentTimeMillis()
        val threshold = now - (maxAgeMinutes * 60 * 1000)
        
        val roomsToRemove = rooms.filter { (_, room) ->
            room.lastActive < threshold
        }.keys
        
        roomsToRemove.forEach { code ->
            rooms.remove(code)
            logger.info("Deleted stale room: {}", code)
        }
    }
    
    fun getAllRooms(): Map<String, Room> = rooms.toMap()
    
    private fun generateRoomCode(): String {
        var code: String
        do {
            code = (ServerConfig.ROOM_CODE_MIN..ServerConfig.ROOM_CODE_MAX).random().toString()
        } while (rooms.containsKey(code))
        return code
    }
}