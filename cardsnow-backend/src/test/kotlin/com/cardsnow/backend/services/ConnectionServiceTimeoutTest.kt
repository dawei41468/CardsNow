package com.cardsnow.backend.services

import com.cardsnow.backend.config.ServerConfig
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionServiceTimeoutTest {

    private class TestWebSocketSession : WebSocketSession {
        private val _incoming = Channel<Frame>(capacity = Channel.UNLIMITED)
        private val _outgoing = Channel<Frame>(capacity = Channel.UNLIMITED)
        private val job = SupervisorJob()

        override val coroutineContext = job + Dispatchers.Unconfined
        override val incoming: ReceiveChannel<Frame> get() = _incoming
        override val outgoing: SendChannel<Frame> get() = _outgoing
        override val extensions: List<io.ktor.websocket.WebSocketExtension<*>> = emptyList()
        override var maxFrameSize: Long = Long.MAX_VALUE
        override var masking: Boolean = false
        override suspend fun flush() { }
        override fun terminate() { _incoming.close(); _outgoing.close() }
        fun offerIncoming(frame: Frame) { _incoming.trySend(frame) }
        fun drainSentTexts(): List<String> {
            val list = mutableListOf<String>()
            while (true) {
                val res = _outgoing.tryReceive()
                if (res.isSuccess) {
                    val f = res.getOrNull()
                    if (f is Frame.Text) list.add(f.readText())
                } else break
            }
            return list
        }
    }

    @Test
    fun operation_timeout_emits_error() = runBlocking {
        val roomService = RoomService()
        val gameService = GameService()
        val connectionService = ConnectionService(roomService, gameService)
        connectionService.opTimeoutMs = 50L
        val session = TestWebSocketSession()

        connectionService.beforeOperation = {
            delay(100)
        }

        val job = launch { connectionService.handleConnection(session) }

        val joinJson = "{" +
                "\"messageType\":\"join_room\"," +
                "\"roomCode\":\"1234\"," +
                "\"playerName\":\"Alice\"" +
                "}"
        session.offerIncoming(Frame.Text(joinJson))
        delay(200)
        val timeoutError = session.drainSentTexts().any { it.contains("\"messageType\":\"error\"") && it.contains("timed out") }
        assertTrue("Expected timeout error to be sent", timeoutError)
        job.cancelAndJoin()
        session.terminate()
    }
}
