package com.cardsnow.backend.plugins

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import com.cardsnow.backend.models.WebSocketMessage

val json = Json {
    classDiscriminator = "messageType"
    explicitNulls = false
    encodeDefaults = true
}

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(json)
    }
}