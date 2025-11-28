package com.example.cardsnow.ws

import com.example.cardsnow.wire.WebSocketMessage as WireMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpAckTrackerTest {

    @Test
    fun onSend_times_out_when_no_ack() = runBlocking {
        val tracker = OpAckTracker(this, timeoutMs = 50L, maxTracked = 8)
        val prepared = tracker.ensureOpId(WireMessage.DrawCard(roomCode = "1234", playerName = "Alice"))
        val deferred = tracker.onSend(prepared)
        val result = deferred.await()
        assertTrue(result is OpAckTracker.AckResult.Timeout)
    }

    @Test
    fun pendingForReplay_marks_and_returns_once_and_clears_on_success() = runBlocking {
        val tracker = OpAckTracker(this, timeoutMs = 1_000L, maxTracked = 8)
        val prepared = tracker.ensureOpId(WireMessage.DrawCard(roomCode = "1234", playerName = "Alice"))
        tracker.onSend(prepared)

        val first = tracker.pendingForReplay()
        assertEquals(1, first.size)

        val second = tracker.pendingForReplay()
        assertEquals(0, second.size)

        val opId = (prepared as WireMessage.DrawCard).opId
        assertTrue(opId != null && opId.isNotBlank())
        tracker.onSuccess(opId, "OK")

        val third = tracker.pendingForReplay()
        assertEquals(0, third.size)
    }
}
