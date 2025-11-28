package com.cardsnow.backend.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import com.cardsnow.backend.config.ServerConfig

fun Application.configureCORS() {
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.AccessControlAllowOrigin)
        allowCredentials = true
        if (ServerConfig.CORS_ALLOW_ANY_HOST) {
            anyHost()
        } else {
            ServerConfig.CORS_ALLOWED_HOSTS.forEach { host ->
                allowHost(host, listOf("http", "https"))
            }
        }
    }
}