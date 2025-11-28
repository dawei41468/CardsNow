package com.cardsnow.backend.plugins

import com.cardsnow.backend.services.ConnectionService
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.isActive
import java.time.Duration
import com.cardsnow.backend.config.ServerConfig

fun Application.configureWebSockets(connectionService: ConnectionService) {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = ServerConfig.WS_MAX_FRAME_BYTES
        masking = false
    }
    
    routing {
        webSocket("/ws") {
            // Handle WebSocket connection
            connectionService.handleConnection(this)
        }
    }
}