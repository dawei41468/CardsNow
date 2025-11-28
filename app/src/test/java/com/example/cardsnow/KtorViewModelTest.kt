package com.example.cardsnow

import com.example.cardsnow.ws.WsClient
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import com.example.cardsnow.wire.WebSocketMessage as WireMessage
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtorViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeWsClient(var connected: Boolean = false) : WsClient {
        val sent = mutableListOf<Frame>()
        override suspend fun send(frame: Frame) {
            sent.add(frame)
        }
        override val isConnected: Boolean
            get() = connected
    }

    @Test
    fun ack_correlation_replays_until_acked_then_stops() = runBlocking {
        val vm = KtorViewModel()
        val fake = FakeWsClient(connected = true)
        vm.setWsClientForTest(fake)
        vm.setConnectedForTest(true)

        // Send one operation (enqueue + flush to avoid Main dispatcher)
        val msg = WireMessage.DrawCard(roomCode = "1234", playerName = "Alice")
        vm.enqueueForTest(msg)
        vm.flushOutgoingQueueForTest()
        assertEquals(1, fake.sent.size)

        val json = Json { classDiscriminator = "messageType" }
        val firstPayload = (fake.sent[0] as Frame.Text).readText()
        val firstMsg = json.decodeFromString<WireMessage>(firstPayload)
        assertTrue(firstMsg is WireMessage.DrawCard)
        val opId = (firstMsg as WireMessage.DrawCard).opId
        assertTrue(!opId.isNullOrBlank())

        // Request pending ops to be replayed (e.g., on reconnect)
        vm.triggerEnqueuePendingOpsForReplayForTest()
        vm.flushOutgoingQueueForTest()
        assertEquals(2, fake.sent.size)
        val secondPayload = (fake.sent[1] as Frame.Text).readText()
        val secondMsg = json.decodeFromString<WireMessage>(secondPayload)
        assertTrue(secondMsg is WireMessage.DrawCard)
        val replayOpId = (secondMsg as WireMessage.DrawCard).opId
        assertEquals(opId, replayOpId)

        // Ack success resolves the pending and prevents further replays
        vm.ackSuccessForTest(opId!!, "OK")
        vm.triggerEnqueuePendingOpsForReplayForTest()
        vm.flushOutgoingQueueForTest()
        assertEquals(2, fake.sent.size)
    }

    @Test
    fun outgoing_queue_flushes_on_reconnect() = runBlocking {
        val vm = KtorViewModel()
        vm.setConnectedForTest(false)

        val msg = WireMessage.ShuffleDeck(roomCode = "1234", playerName = "Alice")
        vm.enqueueForTest(msg)

        val fake = FakeWsClient(connected = true)
        vm.setWsClientForTest(fake)
        vm.setConnectedForTest(true)

        vm.flushOutgoingQueueForTest()

        assertEquals(1, fake.sent.size)
        val frame = fake.sent.first()
        assertTrue(frame is Frame.Text)
        val payload = (frame as Frame.Text).readText()
        assertTrue(payload.contains("\"messageType\":\"shuffle_deck\""))
        assertTrue(payload.contains("\"roomCode\":\"1234\""))
        assertTrue(payload.contains("\"playerName\":\"Alice\""))
    }

    @Test
    fun ping_is_sent_when_connected() = runBlocking {
        val vm = KtorViewModel()
        val fake = FakeWsClient(connected = true)
        vm.setWsClientForTest(fake)
        vm.setConnectedForTest(true)

        vm.pingOnceForTest()

        assertEquals(1, fake.sent.size)
        assertTrue(fake.sent.first() is Frame.Ping)
    }

    @Test
    fun flush_is_gated_until_ack_then_flushes() = runBlocking {
        val vm = KtorViewModel()
        val fake = FakeWsClient(connected = true)
        vm.setWsClientForTest(fake)
        vm.setConnectedForTest(true)

        val msg = WireMessage.DrawCard(roomCode = "1234", playerName = "Alice")
        vm.enqueueForTest(msg)

        // Not acknowledged yet
        vm.setFlushAllowedForTest(false)
        vm.flushOutgoingQueueRespectingGateForTest()
        assertEquals(0, fake.sent.size)

        // Acknowledged (e.g., after SessionCreated/GameStateUpdate)
        vm.setFlushAllowedForTest(true)
        vm.flushOutgoingQueueRespectingGateForTest()
        assertEquals(1, fake.sent.size)
        assertTrue(fake.sent.first() is Frame.Text)
    }
}
