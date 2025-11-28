package com.cardsnow.backend

import com.cardsnow.backend.plugins.*
import com.cardsnow.backend.services.ConnectionService
import com.cardsnow.backend.services.GameService
import com.cardsnow.backend.services.RoomService
import com.cardsnow.backend.services.MetricsService
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val roomService = RoomService()
    val gameService = GameService()
    val connectionService = ConnectionService(roomService, gameService)
    MetricsService.setRoomService(roomService)
    
    configureRouting()
    configureSerialization()
    configureMonitoring()
    configureCORS()
    configureWebSockets(connectionService)
}