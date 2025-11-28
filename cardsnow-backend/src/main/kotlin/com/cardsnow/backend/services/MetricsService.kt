package com.cardsnow.backend.services

import com.cardsnow.backend.models.ErrorCode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable

@Serializable
data class MetricsSnapshot(
    val rooms_total: Int,
    val players_total: Int,
    val ws_connections_current: Long,
    val ws_sessions_known: Long,
    val messages_total: Long,
    val messages_per_minute: Int,
    val errors_total_by_code: Map<String, Long>,
    val dlq_messages_total: Long,
    val dlq_rooms: Int
)

object MetricsService {
    @Volatile
    private var roomServiceRef: RoomService? = null

    private val messagesTotal = AtomicLong(0)
    private val recentMessageTimestamps = ConcurrentLinkedDeque<Long>()

    private val wsConnectionsCurrent = AtomicLong(0)
    private val wsSessionsKnown = AtomicLong(0)

    private val errorsTotalByCode = ConcurrentHashMap<String, AtomicLong>()
    private val unspecifiedErrors = AtomicLong(0)

    private val dlqMessagesTotal = AtomicLong(0)
    private val dlqRooms = ConcurrentHashMap.newKeySet<String>()

    fun setRoomService(roomService: RoomService) {
        roomServiceRef = roomService
    }

    fun onIncomingMessage() {
        messagesTotal.incrementAndGet()
        val now = System.currentTimeMillis()
        recentMessageTimestamps.addLast(now)
        val cutoff = now - 60_000
        while (true) {
            val first = recentMessageTimestamps.peekFirst() ?: break
            if (first < cutoff) {
                recentMessageTimestamps.pollFirst()
            } else break
        }
    }

    fun onConnectionAdded() {
        wsConnectionsCurrent.incrementAndGet()
    }

    fun onConnectionRemoved() {
        val v = wsConnectionsCurrent.decrementAndGet()
        if (v < 0) wsConnectionsCurrent.set(0)
    }

    fun onSessionCreated() {
        wsSessionsKnown.incrementAndGet()
    }

    fun onSessionRemoved() {
        val v = wsSessionsKnown.decrementAndGet()
        if (v < 0) wsSessionsKnown.set(0)
    }

    fun onError(code: ErrorCode?) {
        if (code == null) {
            unspecifiedErrors.incrementAndGet()
            return
        }
        val key = code.name
        errorsTotalByCode.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
    }

    fun onDLQAdd(roomCode: String) {
        dlqMessagesTotal.incrementAndGet()
        dlqRooms.add(roomCode)
    }

    fun snapshot(): MetricsSnapshot {
        val roomService = roomServiceRef
        val rooms = roomService?.getAllRooms() ?: emptyMap()
        val playersTotal = rooms.values.sumOf { it.players.size }
        val errorsMap = HashMap<String, Long>()
        errorsTotalByCode.forEach { (k, v) -> errorsMap[k] = v.get() }
        if (unspecifiedErrors.get() > 0) {
            errorsMap["UNSPECIFIED"] = unspecifiedErrors.get()
        }
        val now = System.currentTimeMillis()
        val cutoff = now - 60_000
        while (true) {
            val first = recentMessageTimestamps.peekFirst() ?: break
            if (first < cutoff) recentMessageTimestamps.pollFirst() else break
        }
        val perMinute = recentMessageTimestamps.size
        return MetricsSnapshot(
            rooms_total = rooms.size,
            players_total = playersTotal,
            ws_connections_current = wsConnectionsCurrent.get(),
            ws_sessions_known = wsSessionsKnown.get(),
            messages_total = messagesTotal.get(),
            messages_per_minute = perMinute,
            errors_total_by_code = errorsMap,
            dlq_messages_total = dlqMessagesTotal.get(),
            dlq_rooms = dlqRooms.size
        )
    }
}
