package com.cardsnow.backend.services

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import org.junit.Assert.assertTrue
import org.junit.Test
import io.ktor.websocket.*
import io.ktor.websocket.readText

class ConnectionServiceJoinThrottlingTest {

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
        override suspend fun flush() {}
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
    fun join_is_rate_limited_per_ip_window() = runBlocking {
        val roomService = RoomService()
        val gameService = GameService()
        val connectionService = ConnectionService(roomService, gameService)

        // Create a room
        val host = TestWebSocketSession()
        val hostJob = launch { connectionService.handleConnection(host) }
        val createJson = "{" +
                "\"messageType\":\"create_room\"," +
                "\"settings\":{\"numDecks\":1,\"includeJokers\":false,\"dealCount\":5}," +
                "\"hostName\":\"Alice\"" +
                "}"
        host.offerIncoming(Frame.Text(createJson))
        delay(150)
        val outs = host.drainSentTexts()
        val roomCode = outs.first { it.contains("\"messageType\":\"room_created\"") }
            .let { Regex("\\\"roomCode\\\":\\\"(\\d{4})\\\"").find(it)!!.groupValues[1] }

        // Attempt two joins from the same IP within the window
        val joiner = TestWebSocketSession()
        val joinJob = launch { connectionService.handleConnection(joiner) }
        val join1 = "{" +
                "\"messageType\":\"join_room\"," +
                "\"roomCode\":\"$roomCode\"," +
                "\"playerName\":\"Bob\"" +
                "}"
        joiner.offerIncoming(Frame.Text(join1))
        delay(100)

        val join2 = "{" +
                "\"messageType\":\"join_room\"," +
                "\"roomCode\":\"$roomCode\"," +
                "\"playerName\":\"Carol\"" +
                "}"
        joiner.offerIncoming(Frame.Text(join2))
        delay(100)

        val joinerOuts = joiner.drainSentTexts()
        val rateLimitedError = joinerOuts.any { it.contains("\"messageType\":\"error\"") && it.contains("Join rate limited") }
        assertTrue("Expected a join rate-limited error on second attempt", rateLimitedError)

        joinJob.cancelAndJoin(); joiner.terminate()
        hostJob.cancelAndJoin(); host.terminate()
    }
}
