package com.cardsnow.backend.services

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.ktor.websocket.*
import io.ktor.websocket.readText

class ConnectionServicePresenceTest {

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
    fun presence_marked_false_on_disconnect_within_grace() = runBlocking {
        val roomService = RoomService()
        val gameService = GameService()
        val connectionService = ConnectionService(roomService, gameService)
        val session = TestWebSocketSession()

        val job = launch { connectionService.handleConnection(session) }
        val createJson = "{" +
                "\"messageType\":\"create_room\"," +
                "\"settings\":{\"numDecks\":1,\"includeJokers\":false,\"dealCount\":5}," +
                "\"hostName\":\"Alice\"" +
                "}"
        session.offerIncoming(Frame.Text(createJson))
        delay(150)

        val outs = session.drainSentTexts()
        val roomCode = outs.first { it.contains("\"messageType\":\"room_created\"") }
            .let { Regex("\\\"roomCode\\\":\\\"(\\d{4})\\\"").find(it)!!.groupValues[1] }
        job.cancelAndJoin()
        session.terminate()

        val room = roomService.getRoom(roomCode)!!
        val alice = room.players["Alice"]
        assertTrue(alice != null)
        assertEquals(false, alice!!.isConnected)
    }

    @Test
    fun reconnection_within_grace_restores_presence() = runBlocking {
        val roomService = RoomService()
        val gameService = GameService()
        val connectionService = ConnectionService(roomService, gameService)
        val s1 = TestWebSocketSession()

        val job1 = launch { connectionService.handleConnection(s1) }
        val createJson = "{" +
                "\"messageType\":\"create_room\"," +
                "\"settings\":{\"numDecks\":1,\"includeJokers\":false,\"dealCount\":5}," +
                "\"hostName\":\"Alice\"" +
                "}"
        s1.offerIncoming(Frame.Text(createJson))
        delay(150)
        val outs = s1.drainSentTexts()
        val roomCode = outs.first { it.contains("\"messageType\":\"room_created\"") }
            .let { Regex("\\\"roomCode\\\":\\\"(\\d{4})\\\"").find(it)!!.groupValues[1] }
        val sessionId = outs.first { it.contains("\"messageType\":\"session_created\"") }
            .let { Regex("\\\"sessionId\\\":\\\"([^\\\"]+)\\\"").find(it)!!.groupValues[1] }
        job1.cancelAndJoin()
        s1.terminate()

        val s2 = TestWebSocketSession()
        val job2 = launch { connectionService.handleConnection(s2) }
        val reconnectJson = "{" +
                "\"messageType\":\"reconnect\"," +
                "\"roomCode\":\"$roomCode\"," +
                "\"playerName\":\"Alice\"," +
                "\"sessionId\":\"$sessionId\"" +
                "}"
        s2.offerIncoming(Frame.Text(reconnectJson))
        delay(150)

        val room = roomService.getRoom(roomCode)!!
        val alice = room.players["Alice"]
        assertTrue(alice != null)
        assertEquals(true, alice!!.isConnected)

        job2.cancelAndJoin()
        s2.terminate()
    }

    @Test
    fun after_grace_without_reconnect_player_left_is_broadcast_and_host_migrates() = runBlocking {
        val roomService = RoomService()
        val gameService = GameService()
        val connectionService = ConnectionService(roomService, gameService)
        // Make grace short for test
        connectionService.disconnectGraceMs = 50L

        // Host Alice creates room
        val alice = TestWebSocketSession()
        val aliceJob = launch { connectionService.handleConnection(alice) }
        val createJson = "{" +
                "\"messageType\":\"create_room\"," +
                "\"settings\":{\"numDecks\":1,\"includeJokers\":false,\"dealCount\":5}," +
                "\"hostName\":\"Alice\"" +
                "}"
        alice.offerIncoming(Frame.Text(createJson))
        delay(150)
        val outs = alice.drainSentTexts()
        val roomCode = outs.first { it.contains("\"messageType\":\"room_created\"") }
            .let { Regex("\\\"roomCode\\\":\\\"(\\d{4})\\\"").find(it)!!.groupValues[1] }

        // Bob joins and stays connected to observe broadcast
        val bob = TestWebSocketSession()
        val bobJob = launch { connectionService.handleConnection(bob) }
        val joinBob = "{" +
                "\"messageType\":\"join_room\"," +
                "\"roomCode\":\"$roomCode\"," +
                "\"playerName\":\"Bob\"" +
                "}"
        bob.offerIncoming(Frame.Text(joinBob))
        delay(150)
        bob.drainSentTexts() // clear previous messages

        // Alice disconnects
        aliceJob.cancelAndJoin(); alice.terminate()

        // Wait beyond grace
        delay(200)

        val bobOuts = bob.drainSentTexts()
        val leftMsg = bobOuts.find { it.contains("\"messageType\":\"player_left\"") }
        assertTrue("Expected player_left broadcast to Bob", leftMsg != null)
        assertTrue(leftMsg!!.contains("\"playerName\":\"Alice\""))
        // newHost should be Bob now
        assertTrue(leftMsg.contains("\"newHost\":\"Bob\""))

        bobJob.cancelAndJoin(); bob.terminate()
    }
}
