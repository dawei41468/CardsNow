package com.example.cardsnow.ws

import com.example.cardsnow.ClientConfig
import com.example.cardsnow.wire.ErrorCode
import com.example.cardsnow.wire.ErrorType
import com.example.cardsnow.wire.WebSocketMessage
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OpAckTracker(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = ClientConfig.OP_TIMEOUT_MS,
    private val maxTracked: Int = ClientConfig.OP_CACHE_MAX
) {

    sealed class AckResult {
        data class Success(val message: String) : AckResult()
        data class Error(val message: String, val code: ErrorCode?, val type: ErrorType) : AckResult()
        data class Failed(val throwable: Throwable) : AckResult()
        object Timeout : AckResult()
        object NotTracked : AckResult()
    }

    private data class Pending(
        var message: WebSocketMessage,
        val deferred: CompletableDeferred<AckResult>,
        val timeoutJob: Job,
        var queuedForReplay: Boolean = false
    )

    private val mutex = Mutex()
    private val pending = LinkedHashMap<String, Pending>(16, 0.75f, false)
    private val completedNotTracked = CompletableDeferred<AckResult>().apply { complete(AckResult.NotTracked) }

    fun ensureOpId(message: WebSocketMessage): WebSocketMessage {
        val existing = message.extractOpId()
        return if (existing.isNullOrBlank()) {
            message.withOpId(generateOpId())
        } else {
            message
        }
    }

    suspend fun onSend(message: WebSocketMessage): CompletableDeferred<AckResult> {
        val opId = message.extractOpId() ?: return completedNotTracked
        return mutex.withLock {
            pending[opId]?.let { entry ->
                entry.message = message
                entry.queuedForReplay = false
                entry.deferred
            } ?: run {
                val deferred = CompletableDeferred<AckResult>()
                val timeoutJob = scope.launch {
                    delay(timeoutMs)
                    val removed = mutex.withLock { pending.remove(opId) }
                    removed?.deferred?.complete(AckResult.Timeout)
                }
                val entry = Pending(message, deferred, timeoutJob, queuedForReplay = false)
                pending[opId] = entry
                trimIfNeeded()
                deferred
            }
        }
    }

    suspend fun onSuccess(opId: String?, message: String): Boolean {
        if (opId.isNullOrBlank()) return false
        val entry = mutex.withLock { pending.remove(opId) } ?: return false
        entry.timeoutJob.cancel()
        entry.deferred.complete(AckResult.Success(message))
        return true
    }

    suspend fun onError(opId: String?, message: String, code: ErrorCode?, type: ErrorType): Boolean {
        if (opId.isNullOrBlank()) return false
        val entry = mutex.withLock { pending.remove(opId) } ?: return false
        entry.timeoutJob.cancel()
        entry.deferred.complete(AckResult.Error(message, code, type))
        return true
    }

    suspend fun onSendFailed(opId: String, throwable: Throwable) {
        val entry = mutex.withLock { pending.remove(opId) } ?: return
        entry.timeoutJob.cancel()
        entry.deferred.complete(AckResult.Failed(throwable))
    }

    suspend fun pendingForReplay(): List<WebSocketMessage> = mutex.withLock {
        pending.values
            .filter { !it.queuedForReplay }
            .onEach { it.queuedForReplay = true }
            .map { it.message }
    }

    suspend fun clear() {
        val entries = mutex.withLock {
            val snapshot = pending.values.toList()
            pending.clear()
            snapshot
        }
        entries.forEach { entry ->
            entry.timeoutJob.cancel()
            entry.deferred.cancel()
        }
    }

    private fun trimIfNeeded() {
        if (pending.size <= maxTracked) return
        val iterator = pending.entries.iterator()
        if (iterator.hasNext()) {
            val eldest = iterator.next()
            iterator.remove()
            eldest.value.timeoutJob.cancel()
            eldest.value.deferred.complete(AckResult.Timeout)
        }
    }

    private fun generateOpId(): String = UUID.randomUUID().toString()

    private fun WebSocketMessage.extractOpId(): String? = when (this) {
        is WebSocketMessage.JoinRoom -> this.opId
        is WebSocketMessage.CreateRoom -> this.opId
        is WebSocketMessage.StartGame -> this.opId
        is WebSocketMessage.PlayCards -> this.opId
        is WebSocketMessage.DiscardCards -> this.opId
        is WebSocketMessage.DrawCard -> this.opId
        is WebSocketMessage.DrawFromDiscard -> this.opId
        is WebSocketMessage.ShuffleDeck -> this.opId
        is WebSocketMessage.DealCards -> this.opId
        is WebSocketMessage.MoveCards -> this.opId
        is WebSocketMessage.RecallLastPile -> this.opId
        is WebSocketMessage.RecallLastDiscard -> this.opId
        is WebSocketMessage.SortHand -> this.opId
        is WebSocketMessage.ReorderHand -> this.opId
        is WebSocketMessage.RestartGame -> this.opId
        is WebSocketMessage.Reconnect -> this.opId
        else -> null
    }

    private fun WebSocketMessage.withOpId(opId: String): WebSocketMessage = when (this) {
        is WebSocketMessage.JoinRoom -> copy(opId = opId)
        is WebSocketMessage.CreateRoom -> copy(opId = opId)
        is WebSocketMessage.StartGame -> copy(opId = opId)
        is WebSocketMessage.PlayCards -> copy(opId = opId)
        is WebSocketMessage.DiscardCards -> copy(opId = opId)
        is WebSocketMessage.DrawCard -> copy(opId = opId)
        is WebSocketMessage.DrawFromDiscard -> copy(opId = opId)
        is WebSocketMessage.ShuffleDeck -> copy(opId = opId)
        is WebSocketMessage.DealCards -> copy(opId = opId)
        is WebSocketMessage.MoveCards -> copy(opId = opId)
        is WebSocketMessage.RecallLastPile -> copy(opId = opId)
        is WebSocketMessage.RecallLastDiscard -> copy(opId = opId)
        is WebSocketMessage.SortHand -> copy(opId = opId)
        is WebSocketMessage.ReorderHand -> copy(opId = opId)
        is WebSocketMessage.RestartGame -> copy(opId = opId)
        is WebSocketMessage.Reconnect -> copy(opId = opId)
        else -> this
    }
}
