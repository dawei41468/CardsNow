package com.example.cardsnow

object ClientConfig {
    const val WS_URL: String = "ws://10.0.2.2:8080/ws"
    const val REQUEST_TIMEOUT_MS: Long = 30_000L
    const val RECONNECT_BACKOFF_BASE_MS: Long = 1_000L
    const val RECONNECT_BACKOFF_MAX_SHIFT: Int = 5
    const val RECONNECT_BACKOFF_MAX_MS: Long = 30_000L
    const val RECONNECT_JITTER_MAX_MS: Long = 500L
    const val PING_INTERVAL_MS: Long = 30_000L
    const val OUTGOING_BUFFER_MAX: Int = 10
    const val ERROR_AUTO_DISMISS_MS: Long = 3_000L
    const val SUCCESS_AUTO_DISMISS_MS: Long = 2_000L
    val IS_DEBUG: Boolean by lazy {
        try {
            val clazz = Class.forName("com.example.cardsnow.BuildConfig")
            val field = clazz.getField("DEBUG")
            field.getBoolean(null)
        } catch (e: Exception) {
            false
        }
    }
}
