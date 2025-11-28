package com.example.cardsnow.ws

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.send

interface WsClient {
    suspend fun send(frame: Frame)
    val isConnected: Boolean

    object Noop : WsClient {
        override suspend fun send(frame: Frame) { /* no-op */ }
        override val isConnected: Boolean = false
    }

    class Session(private val sessionProvider: () -> WebSocketSession?) : WsClient {
        override suspend fun send(frame: Frame) {
            val session = sessionProvider() ?: return
            session.send(frame)
        }
        override val isConnected: Boolean
            get() = sessionProvider() != null
    }
}
