package com.cardsnow.backend.models

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64

@Serializable
data class Session(
    val sessionId: String,
    val playerName: String,
    val roomCode: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastActivity: Long = System.currentTimeMillis()
) {
    companion object {
        private val secureRandom = SecureRandom()
        private const val SESSION_ID_LENGTH = 32
        
        fun generateSessionId(): String {
            val bytes = ByteArray(SESSION_ID_LENGTH)
            secureRandom.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
        
        fun create(playerName: String, roomCode: String): Session {
            return Session(
                sessionId = generateSessionId(),
                playerName = playerName,
                roomCode = roomCode
            )
        }
    }
    
    fun isExpired(ttlHours: Int = 24): Boolean {
        val ttlMillis = ttlHours * 60 * 60 * 1000L
        return (System.currentTimeMillis() - lastActivity) > ttlMillis
    }
    
    fun withUpdatedActivity(): Session {
        return copy(lastActivity = System.currentTimeMillis())
    }
}