package com.cardsnow.backend.services

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import org.junit.Assert.assertTrue
import org.junit.Test
import io.ktor.websocket.*
import io.ktor.websocket.readText

class ConnectionServiceTest {

    private class TestWebSocketSession : WebSocketSession {
        private val _incoming = Channel<Frame>(capacity = Channel.UNLIMITED)
        private val _outgoing = Channel<Frame>(capacity = Channel.UNLIMITED)
        private val job = SupervisorJob()

        override val coroutineContext = job + Dispatchers.Unconfined
        override val incoming: ReceiveChannel<Frame> get() = _incoming
        override val outgoing: SendChannel<Frame> get() = _outgoing
        override val extensions: List<WebSocketExtension<*>> = emptyList()
        override var maxFrameSize: Long = Long.MAX_VALUE
        override var masking: Boolean = false
        override suspend fun flush() { /* no-op */ }
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
    fun oversized_payload_is_rejected_with_error() = runBlocking {
        val roomService = RoomService()
        val gameService = GameService()
        val connectionService = ConnectionService(roomService, gameService)
        val session = TestWebSocketSession()

        val tooBig = "x".repeat(com.cardsnow.backend.config.ServerConfig.MAX_INCOMING_MESSAGE_BYTES + 1)
        val job = launch { connectionService.handleConnection(session) }
        session.offerIncoming(Frame.Text(tooBig))
        delay(100)
        val anyError = session.drainSentTexts().any { it.contains("\"messageType\":\"error\"") && it.contains("Message too large") }
        assertTrue("Expected an error about message being too large", anyError)
        job.cancelAndJoin()
        session.terminate()
    }

    @Test
    fun small_join_room_message_is_processed() = runBlocking {
        val roomService = RoomService()
        val gameService = GameService()
        val connectionService = ConnectionService(roomService, gameService)
        val session = TestWebSocketSession()

        val joinJson = "{" +
                "\"messageType\":\"join_room\"," +
                "\"roomCode\":\"1234\"," +
                "\"playerName\":\"Alice\"" +
                "}"
        val job = launch { connectionService.handleConnection(session) }
        session.offerIncoming(Frame.Text(joinJson))
        delay(100)

        // Should not hit the payload-too-large error
        val tooLargeError = session.drainSentTexts().any { it.contains("Message too large") }
        assertTrue("Did not expect payload-too-large error", !tooLargeError)
        job.cancelAndJoin()
        session.terminate()
    }
}
