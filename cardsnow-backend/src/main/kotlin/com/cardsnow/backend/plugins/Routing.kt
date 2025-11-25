package com.cardsnow.backend.plugins

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("CardsNow Backend API")
        }
        
        get("/health") {
            call.respondText("OK")
        }
        
        get("/status") {
            call.respond(mapOf("status" to "running", "version" to "0.0.1"))
        }
    }
}