package com.cardsnow.backend.config

object ServerConfig {
    const val CREATE_ROOM_RATE_WINDOW_MS: Long = 60_000L
    const val MESSAGE_RATE_WINDOW_MS: Long = 1_000L
    const val MESSAGE_RATE_MAX_PER_WINDOW: Int = 10

    const val CLEANUP_INTERVAL_MS: Long = 5 * 60 * 1000L
    const val OLD_ROOM_MAX_AGE_MINUTES: Int = 30

    const val SEND_RETRY_MAX_ATTEMPTS: Int = 3
    const val SEND_RETRY_BASE_MS: Long = 100L

    const val ROOM_CODE_MIN: Int = 1000
    const val ROOM_CODE_MAX: Int = 9999

    const val SESSION_TTL_HOURS: Int = 24
    const val LOCK_TIMEOUT_MS: Long = 5_000L
    const val WS_MAX_FRAME_BYTES: Long = 10 * 1024L
    const val MAX_INCOMING_MESSAGE_BYTES: Int = 10 * 1024
    const val OP_TIMEOUT_MS: Long = 10_000L
}
