package com.cardsnow.backend.plugins

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.cardsnow.backend.services.MetricsService

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("CardsNow Backend API")
        }
        
        get("/health") {
            call.respondText("OK")
        }
        
        get("/ready") {
            call.respondText("READY")
        }
        
        get("/status") {
            call.respond(mapOf("status" to "running", "version" to "0.0.1"))
        }

        get("/metrics") {
            call.respond(MetricsService.snapshot())
        }
    }
}