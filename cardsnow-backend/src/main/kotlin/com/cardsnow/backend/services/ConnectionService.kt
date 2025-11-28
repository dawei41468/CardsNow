package com.cardsnow.backend.services

import com.cardsnow.backend.models.*
import com.cardsnow.backend.models.ErrorType
import com.cardsnow.backend.config.ServerConfig
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.isActive
import com.cardsnow.backend.plugins.json
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

import java.util.ArrayDeque
import java.util.Deque
import java.util.Collections
import java.util.LinkedHashMap
import io.ktor.server.request.*
import io.ktor.server.websocket.DefaultWebSocketServerSession
import org.slf4j.LoggerFactory

class ConnectionService(
    private val roomService: RoomService,
    private val gameService: GameService
) {

    private val messageTimestamps = ConcurrentHashMap<String, Deque<Long>>()
    private val connections = ConcurrentHashMap<String, MutableSet<WebSocketSession>>()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val sessionToWebSocket = ConcurrentHashMap<String, WebSocketSession>()
    private val deadLetterQueue = ConcurrentHashMap<String, MutableList<String>>()
    private val opCache = ConcurrentHashMap<String, MutableMap<String, String>>()
    private val ipCreateTimestamps = ConcurrentHashMap<String, Deque<Long>>()
    private val ipJoinTimestamps = ConcurrentHashMap<String, Deque<Long>>()
    private val pendingDisconnectRemoval = ConcurrentHashMap<String, Job>()
    private val logger = LoggerFactory.getLogger(ConnectionService::class.java)
    @Volatile var beforeOperation: suspend () -> Unit = { }
    @Volatile var opTimeoutMs: Long = ServerConfig.OP_TIMEOUT_MS
    @Volatile var disconnectGraceMs: Long = ServerConfig.DISCONNECT_GRACE_MS
    
    init {
        // Start session cleanup job
        CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                delay(ServerConfig.CLEANUP_INTERVAL_MS) // Run every 5 minutes
                cleanupExpiredSessions()
            }
        }

        // Start room cleanup job
        CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                delay(ServerConfig.CLEANUP_INTERVAL_MS) // Run every 5 minutes
                roomService.cleanupOldRooms(ServerConfig.OLD_ROOM_MAX_AGE_MINUTES)
            }
        }
    }

    private suspend fun runWithTimeout(session: WebSocketSession, block: suspend () -> Unit) {
        try {
            withTimeout(opTimeoutMs) {
                beforeOperation()
                block()
            }
        } catch (e: TimeoutCancellationException) {
            val seconds = opTimeoutMs / 1000
            sendError(session, "Operation timed out after ${seconds} seconds.", ErrorType.TRANSIENT, ErrorCode.TIMEOUT)
        }
    }
    
    suspend fun handleConnection(session: WebSocketSession) {
        MetricsService.onConnectionAdded()
        try {
            session.incoming.consumeEach { frame ->
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    handleMessage(session, text)
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            logger.info("WebSocket connection closed: ${e.message}")
        } catch (e: Exception) {
            logger.error("WebSocket error: ${e.message}", e)
        } finally {
            MetricsService.onConnectionRemoved()
            // Clean up disconnected player
            cleanupDisconnectedPlayer(session)
        }
    }
    
    private suspend fun handleMessage(session: WebSocketSession, message: String) {
        MetricsService.onIncomingMessage()
        val sessionObj = getSessionFromWebSocket(session)
        if (sessionObj != null && isMessageRateLimited(sessionObj.sessionId)) {
            sendError(session, "Rate limited. Slow down!", ErrorType.TRANSIENT, ErrorCode.RATE_LIMITED)
            return
        }
        // Enforce incoming payload size limit (bytes)
        val sizeBytes = message.toByteArray(Charsets.UTF_8).size
        if (sizeBytes > ServerConfig.MAX_INCOMING_MESSAGE_BYTES) {
            sendError(session, "Message too large. Limit is ${ServerConfig.MAX_INCOMING_MESSAGE_BYTES} bytes.", ErrorType.TRANSIENT, ErrorCode.PAYLOAD_TOO_LARGE)
            return
        }

        try {
            val webSocketMessage = json.decodeFromString<WebSocketMessage>(message)
            
            when (webSocketMessage) {
                is WebSocketMessage.CreateRoom -> runWithTimeout(session) { handleCreateRoom(session, webSocketMessage) }
                is WebSocketMessage.JoinRoom -> runWithTimeout(session) { handleJoinRoom(session, webSocketMessage) }
                is WebSocketMessage.StartGame -> runWithTimeout(session) { handleStartGame(session, webSocketMessage) }
                is WebSocketMessage.PlayCards -> runWithTimeout(session) { handlePlayCards(session, webSocketMessage) }
                is WebSocketMessage.DiscardCards -> runWithTimeout(session) { handleDiscardCards(session, webSocketMessage) }
                is WebSocketMessage.DrawCard -> runWithTimeout(session) { handleDrawCard(session, webSocketMessage) }
                is WebSocketMessage.DrawFromDiscard -> runWithTimeout(session) { handleDrawFromDiscard(session, webSocketMessage) }
                is WebSocketMessage.ShuffleDeck -> runWithTimeout(session) { handleShuffleDeck(session, webSocketMessage) }
                is WebSocketMessage.DealCards -> runWithTimeout(session) { handleDealCards(session, webSocketMessage) }
                is WebSocketMessage.MoveCards -> runWithTimeout(session) { handleMoveCards(session, webSocketMessage) }
                is WebSocketMessage.RecallLastPile -> runWithTimeout(session) { handleRecallLastPile(session, webSocketMessage) }
                is WebSocketMessage.RecallLastDiscard -> runWithTimeout(session) { handleRecallLastDiscard(session, webSocketMessage) }
                is WebSocketMessage.SortHand -> runWithTimeout(session) { handleSortHand(session, webSocketMessage) }
                is WebSocketMessage.ReorderHand -> runWithTimeout(session) { handleReorderHand(session, webSocketMessage) }
                is WebSocketMessage.RestartGame -> runWithTimeout(session) { handleRestartGame(session, webSocketMessage) }
                is WebSocketMessage.Reconnect -> runWithTimeout(session) { handleReconnect(session, webSocketMessage) }
                else -> {
                    // Unknown message type
                    sendError(session, "Unknown message type", ErrorType.CRITICAL, ErrorCode.UNKNOWN)
                }
            }
        } catch (e: Exception) {
            logger.error("Error handling message from session ${session.hashCode()}: ${e.message}", e)
            sendError(session, "Invalid message format", ErrorType.CRITICAL, ErrorCode.INVALID_FORMAT)
        }
    }
    
    private suspend fun handleCreateRoom(session: WebSocketSession, message: WebSocketMessage.CreateRoom) {
        val ip = getRemoteHost(session) ?: "unknown"
        val timestamps = ipCreateTimestamps.getOrPut(ip) { ArrayDeque() }
        val now = System.currentTimeMillis()
        while (timestamps.isNotEmpty() && timestamps.first < now - ServerConfig.CREATE_ROOM_RATE_WINDOW_MS) {
            timestamps.removeFirst()
        }
        if (timestamps.any { it > now - ServerConfig.CREATE_ROOM_RATE_WINDOW_MS }) {
            val waitSeconds = ServerConfig.CREATE_ROOM_RATE_WINDOW_MS / 1000
            sendError(session, "Room creation rate limited. Wait ${waitSeconds} seconds.", ErrorType.TRANSIENT, ErrorCode.RATE_LIMITED, message.opId)
            return
        }
        timestamps.addLast(now)
        
        // Validate input
        if (!ValidationService.validatePlayerName(message.hostName)) {
            sendError(session, "Invalid player name. Must be 1-20 alphanumeric characters.", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
            return
        }
        if (!ValidationService.validateRoomSettings(message.settings)) {
            sendError(session, "Invalid room settings.", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
            return
        }
        // Idempotency check
        getSessionFromWebSocket(session)?.let { s ->
            message.opId?.let { opId ->
                getCachedAck(s.sessionId, opId)?.let { cached ->
                    session.send(cached)
                    return
                }
            }
        }
        
        val roomCode = roomService.createRoom(message.settings, message.hostName)
        
        // Create session for the host
        val sessionObj = Session.create(message.hostName, roomCode)
        sessions[sessionObj.sessionId] = sessionObj
        sessionToWebSocket[sessionObj.sessionId] = session
        MetricsService.onSessionCreated()
        
        // Add connection tracking
        connections.computeIfAbsent(roomCode) { mutableSetOf() }.add(session)
        
        // Send room created message
        val roomCreatedMessage = WebSocketMessage.RoomCreated(
            roomCode = roomCode,
            players = listOf(message.hostName)
        )
        session.send(json.encodeToString(WebSocketMessage.serializer(), roomCreatedMessage))
        
        // Also send session info
        val sessionMessage = WebSocketMessage.SessionCreated(
            sessionId = sessionObj.sessionId,
            playerName = message.hostName,
            roomCode = roomCode
        )
        session.send(json.encodeToString(WebSocketMessage.serializer(), sessionMessage))

        // Send success message to trigger frontend navigation (idempotent ack)
        sendSuccess(session, "Room $roomCode created!", message.opId)

        logger.info("Room created: {} by {} with session {} from ip {}", roomCode, message.hostName, sessionObj.sessionId, ip)
    }
    
    private suspend fun handleJoinRoom(session: WebSocketSession, message: WebSocketMessage.JoinRoom) {
        // Basic IP throttling for join attempts (similar to create_room)
        val ip = getRemoteHost(session) ?: "unknown"
        val timestamps = ipJoinTimestamps.getOrPut(ip) { ArrayDeque() }
        val now = System.currentTimeMillis()
        while (timestamps.isNotEmpty() && timestamps.first < now - ServerConfig.JOIN_ROOM_RATE_WINDOW_MS) {
            timestamps.removeFirst()
        }
        if (timestamps.any { it > now - ServerConfig.JOIN_ROOM_RATE_WINDOW_MS }) {
            val waitSeconds = ServerConfig.JOIN_ROOM_RATE_WINDOW_MS / 1000
            sendError(session, "Join rate limited. Wait ${waitSeconds} seconds.", ErrorType.TRANSIENT, ErrorCode.RATE_LIMITED, message.opId)
            return
        }
        timestamps.addLast(now)

        // Validate input
        if (!ValidationService.validatePlayerName(message.playerName)) {
            sendError(session, "Invalid player name. Must be 1-20 alphanumeric characters.", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
            return
        }
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND, message.opId)
            return
        }

        val isRejoining = room.players.containsKey(message.playerName)

        if (isRejoining) {
            // Check if player is still connected
            val existingSession = sessions.values.find { it.playerName == message.playerName && it.roomCode == message.roomCode }
            if (existingSession != null && sessionToWebSocket.containsKey(existingSession.sessionId)) {
                sendError(session, "Player name already taken.", ErrorType.TRANSIENT, ErrorCode.CONFLICT, message.opId)
                return
            }
            
            // Player is rejoining, restore session mapping
            if (existingSession != null) {
                sessions[existingSession.sessionId] = existingSession.withUpdatedActivity()
                sessionToWebSocket[existingSession.sessionId] = session
                // Cancel pending removal if scheduled
                pendingDisconnectRemoval.remove(existingSession.sessionId)?.cancel()
                // Mark presence back to connected and broadcast
                try {
                    roomService.executeRoomOperation(message.roomCode) {
                        room.atomicUpdate {
                            val p = room.players[message.playerName]
                            if (p != null && !p.isConnected) {
                                val updatedRoom = room.copy(players = room.players.toMutableMap().apply {
                                    this[message.playerName] = p.copy(isConnected = true)
                                })
                                roomService.updateRoom(message.roomCode, updatedRoom)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Error marking player {} connected in room {}: {}", message.playerName, message.roomCode, e.message)
                }
                roomService.getRoom(message.roomCode)?.let { updated ->
                    broadcastGameStateUpdate(message.roomCode, updated)
                }
            }
            
            connections.computeIfAbsent(message.roomCode) { mutableSetOf() }.add(session)

            // Send current game state to reconnected player
            val gameStateUpdate = WebSocketMessage.GameStateUpdate(
                roomCode = message.roomCode,
                gameState = room.gameState,
                players = room.players
            )
            session.send(json.encodeToString(WebSocketMessage.serializer(), gameStateUpdate))

            sendSuccess(session, "Rejoined room ${message.roomCode} as ${message.playerName}!", message.opId)

            logger.info("Player {} rejoined room {}", message.playerName, message.roomCode)
        } else {
            // New player joining
            if (room.players.containsKey(message.playerName)) {
                sendError(session, "Player name already taken.", ErrorType.TRANSIENT, ErrorCode.CONFLICT)
                return
            }
            
            val success = roomService.joinRoom(message.roomCode, message.playerName)

            if (success) {
                // Create session for new player
                val sessionObj = Session.create(message.playerName, message.roomCode)
                sessions[sessionObj.sessionId] = sessionObj
                sessionToWebSocket[sessionObj.sessionId] = session
                MetricsService.onSessionCreated()
                
                // Add connection tracking
                connections.computeIfAbsent(message.roomCode) { mutableSetOf() }.add(session)

                // Send success response
                sendSuccess(session, "Joined room ${message.roomCode} as ${message.playerName}!", message.opId)
                
                // Send session info
                val sessionMessage = WebSocketMessage.SessionCreated(
                    sessionId = sessionObj.sessionId,
                    playerName = message.playerName,
                    roomCode = message.roomCode
                )
                session.send(json.encodeToString(WebSocketMessage.serializer(), sessionMessage))

                // Broadcast player joined to all room members
                val updatedRoom = roomService.getRoom(message.roomCode)
                updatedRoom?.let {
                    val playerJoinedMessage = WebSocketMessage.PlayerJoined(
                        roomCode = message.roomCode,
                        playerName = message.playerName,
                        players = it.players.keys.toList()
                    )
                    broadcastToRoom(message.roomCode, playerJoinedMessage)
                }

                logger.info("Player {} joined room {}", message.playerName, message.roomCode)
            } else {
                sendError(session, "Failed to join room", ErrorType.CRITICAL, ErrorCode.UNKNOWN, message.opId)
            }
        }
    }
    
    private suspend fun handleStartGame(session: WebSocketSession, message: WebSocketMessage.StartGame) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
        // idempotency check
        message.opId?.let { opId ->
            getCachedAck(sessionObj.sessionId, opId)?.let { cached ->
                session.send(cached)
                return
            }
        }
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
            return
        }

        val player = room.players.values.find { it.isHost }
        if (player?.name != sessionObj.playerName) {
            sendError(session, "Only host can start game", ErrorType.TRANSIENT, ErrorCode.AUTHZ)
            return
        }
        try {
            roomService.executeRoomOperation(message.roomCode) {
                room.atomicUpdate {
                    val gameState = gameService.startGame(room, room.players.keys.toList()).getOrThrow()
                    val updatedRoom = room.copy(gameState = gameState)
                    roomService.updateRoom(message.roomCode, updatedRoom)
                }
            }

            val updatedRoom = roomService.getRoom(message.roomCode)!!
            val gameStateUpdate = WebSocketMessage.GameStateUpdate(
                roomCode = message.roomCode,
                gameState = updatedRoom.gameState,
                players = updatedRoom.players
            )
            broadcastToRoom(message.roomCode, gameStateUpdate)

            sendSuccess(session, "Game started!", message.opId)
            logger.info("Game started in room {}", message.roomCode)
        } catch (e: Exception) {
            sendError(session, e.message ?: "Failed to start game", ErrorType.TRANSIENT, ErrorCode.CONFLICT, message.opId)
        }
    }
    
    private suspend fun handlePlayCards(session: WebSocketSession, message: WebSocketMessage.PlayCards) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
        // idempotency check
        message.opId?.let { opId ->
            getCachedAck(sessionObj.sessionId, opId)?.let { cached ->
                session.send(cached)
                return
            }
        }
        
        // Validate input
        if (!ValidationService.validateCardIds(message.cardIds)) {
            sendError(session, "Invalid card IDs", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
            return
        }
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
            return
        }

        try {
            roomService.executeRoomOperation(message.roomCode) {
                room.atomicUpdate {
                    val updatedGameState = gameService.playCards(room, sessionObj.playerName, message.cardIds).getOrThrow()
                    val updatedRoom = room.copy(gameState = updatedGameState)
                    roomService.updateRoom(message.roomCode, updatedRoom)
                }
            }
            
            // If we get here, operation was successful
            val updatedRoom = roomService.getRoom(message.roomCode)!!
            broadcastGameStateUpdate(message.roomCode, updatedRoom)
            logger.info("{} played {} cards in room {}", sessionObj.playerName, message.cardIds.size, message.roomCode)
            
        } catch (e: Exception) {
            sendError(session, e.message ?: "Failed to play cards", ErrorType.TRANSIENT, ErrorCode.CONFLICT, message.opId)
        }
    }
    
    private suspend fun handleDiscardCards(session: WebSocketSession, message: WebSocketMessage.DiscardCards) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
        // idempotency check
        message.opId?.let { opId ->
            getCachedAck(sessionObj.sessionId, opId)?.let { cached ->
                session.send(cached)
                return
            }
        }
        
        // Validate input
        if (!ValidationService.validateCardIds(message.cardIds)) {
            sendError(session, "Invalid card IDs", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
            return
        }
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
            return
        }
        try {
            roomService.executeRoomOperation(message.roomCode) {
                room.atomicUpdate {
                    val updatedGameState = gameService.discardCards(room, sessionObj.playerName, message.cardIds).getOrThrow()
                    val updatedRoom = room.copy(gameState = updatedGameState)
                    roomService.updateRoom(message.roomCode, updatedRoom)
                }
            }

            val updatedRoom = roomService.getRoom(message.roomCode)!!
            broadcastGameStateUpdate(message.roomCode, updatedRoom)
            logger.info("{} discarded {} cards in room {}", sessionObj.playerName, message.cardIds.size, message.roomCode)
        } catch (e: Exception) {
            sendError(session, e.message ?: "Failed to discard cards", ErrorType.TRANSIENT, ErrorCode.CONFLICT, message.opId)
        }
    }
    
    private suspend fun handleDrawCard(session: WebSocketSession, message: WebSocketMessage.DrawCard) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
        // idempotency check
        message.opId?.let { opId ->
            getCachedAck(sessionObj.sessionId, opId)?.let { cached ->
                session.send(cached)
                return
            }
        }
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
            return
        }

        try {
            roomService.executeRoomOperation(message.roomCode) {
                room.atomicUpdate {
                    val (_, updatedGameState) = gameService.drawCard(room, sessionObj.playerName).getOrThrow()
                    val updatedRoom = room.copy(gameState = updatedGameState)
                    roomService.updateRoom(message.roomCode, updatedRoom)
                }
            }
            
            val updatedRoom = roomService.getRoom(message.roomCode)!!
            broadcastGameStateUpdate(message.roomCode, updatedRoom)
            logger.info("{} drew a card in room {}", sessionObj.playerName, message.roomCode)
            
        } catch (e: Exception) {
            sendError(session, e.message ?: "Failed to draw card", ErrorType.TRANSIENT, ErrorCode.CONFLICT, message.opId)
        }
    }
    
    private suspend fun handleDrawFromDiscard(session: WebSocketSession, message: WebSocketMessage.DrawFromDiscard) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
        // idempotency check
        message.opId?.let { opId ->
            getCachedAck(sessionObj.sessionId, opId)?.let { cached ->
                session.send(cached)
                return
            }
        }
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
            return
        }

        try {
            roomService.executeRoomOperation(message.roomCode) {
                room.atomicUpdate {
                    val updatedGameState = gameService.drawFromDiscard(room, sessionObj.playerName).getOrThrow()
                    val updatedRoom = room.copy(gameState = updatedGameState)
                    roomService.updateRoom(message.roomCode, updatedRoom)
                }
            }
            
            val updatedRoom = roomService.getRoom(message.roomCode)!!
            broadcastGameStateUpdate(message.roomCode, updatedRoom)
            logger.info("{} drew from discard in room {}", sessionObj.playerName, message.roomCode)
            
        } catch (e: Exception) {
            sendError(session, e.message ?: "Failed to draw from discard", ErrorType.TRANSIENT, ErrorCode.CONFLICT, message.opId)
        }
    }
    
    private suspend fun handleShuffleDeck(session: WebSocketSession, message: WebSocketMessage.ShuffleDeck) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
        // idempotency check
        message.opId?.let { opId ->
            getCachedAck(sessionObj.sessionId, opId)?.let { cached ->
                session.send(cached)
                return
            }
        }
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
            return
        }

        try {
            roomService.executeRoomOperation(message.roomCode) {
                room.atomicUpdate {
                    val updatedGameState = gameService.shuffleDeck(room, sessionObj.playerName).getOrThrow()
                    val updatedRoom = room.copy(gameState = updatedGameState)
                    roomService.updateRoom(message.roomCode, updatedRoom)
                }
            }
            
            val updatedRoom = roomService.getRoom(message.roomCode)!!
            broadcastGameStateUpdate(message.roomCode, updatedRoom)
            
            sendSuccess(session, "Deck shuffled!", message.opId)
            logger.info("{} shuffled deck in room {}", sessionObj.playerName, message.roomCode)
            
        } catch (e: Exception) {
            sendError(session, e.message ?: "Failed to shuffle deck", ErrorType.TRANSIENT, ErrorCode.CONFLICT, message.opId)
        }
    }
    
    private suspend fun handleDealCards(session: WebSocketSession, message: WebSocketMessage.DealCards) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
        // idempotency check
        message.opId?.let { opId ->
            getCachedAck(sessionObj.sessionId, opId)?.let { cached ->
                session.send(cached)
                return
            }
        }
        
        // Validate input
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
            return
        }

        // Validate card count based on deck size and player count
        val playerCount = room.players.size
        if (message.count <= 0 || message.count * playerCount > room.gameState.deck.size) {
            sendError(session, "Invalid card count. Must be between 1 and ${room.gameState.deck.size / playerCount}.", ErrorType.TRANSIENT, ErrorCode.VALIDATION)
            return
        }

        try {
            roomService.executeRoomOperation(message.roomCode) {
                room.atomicUpdate {
                    val (_, updatedGameState) = gameService.dealCards(room, sessionObj.playerName, message.count).getOrThrow()
                    val updatedRoom = room.copy(gameState = updatedGameState)
                    roomService.updateRoom(message.roomCode, updatedRoom)
                }
            }
            
            val updatedRoom = roomService.getRoom(message.roomCode)!!
            broadcastGameStateUpdate(message.roomCode, updatedRoom)
            
            sendSuccess(session, "Dealt ${message.count} cards to each player!", message.opId)
            logger.info("{} dealt {} cards in room {}", sessionObj.playerName, message.count, message.roomCode)
            
        } catch (e: Exception) {
            sendError(session, e.message ?: "Failed to deal cards", ErrorType.TRANSIENT, ErrorCode.CONFLICT, message.opId)
        }
    }
    
    private suspend fun handleMoveCards(session: WebSocketSession, message: WebSocketMessage.MoveCards) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
        // idempotency check
        message.opId?.let { opId ->
            getCachedAck(sessionObj.sessionId, opId)?.let { cached ->
                session.send(cached)
                return
            }
        }
        
        // Validate input
        if (!ValidationService.validatePlayerName(message.toPlayer)) {
            sendError(session, "Invalid target player name", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
            return
        }
        if (!ValidationService.validateCardIds(message.cardIds)) {
            sendError(session, "Invalid card IDs", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
            return
        }
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
            return
        }

        try {
            roomService.executeRoomOperation(message.roomCode) {
                room.atomicUpdate {
                    val updatedGameState = gameService.moveCards(room, sessionObj.playerName, message.toPlayer, message.cardIds).getOrThrow()
                    val updatedRoom = room.copy(gameState = updatedGameState)
                    roomService.updateRoom(message.roomCode, updatedRoom)
                }
            }
            
            val updatedRoom = roomService.getRoom(message.roomCode)!!
            broadcastGameStateUpdate(message.roomCode, updatedRoom)
            logger.info("{} moved cards to {} in room {}", sessionObj.playerName, message.toPlayer, message.roomCode)
            
        } catch (e: Exception) {
            sendError(session, e.message ?: "Failed to move cards", ErrorType.TRANSIENT, ErrorCode.CONFLICT, message.opId)
        }
    }
    
    private suspend fun handleRecallLastPile(session: WebSocketSession, message: WebSocketMessage.RecallLastPile) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
        // idempotency check
        message.opId?.let { opId ->
            getCachedAck(sessionObj.sessionId, opId)?.let { cached ->
                session.send(cached)
                return
            }
        }
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
            return
        }

        try {
            roomService.executeRoomOperation(message.roomCode) {
                room.atomicUpdate {
                    val updatedGameState = gameService.recallLastPile(room, sessionObj.playerName).getOrThrow()
                    val updatedRoom = room.copy(gameState = updatedGameState)
                    roomService.updateRoom(message.roomCode, updatedRoom)
                }
            }
            
            val updatedRoom = roomService.getRoom(message.roomCode)!!
            broadcastGameStateUpdate(message.roomCode, updatedRoom)
            
            sendSuccess(session, "Last pile recalled!", message.opId)
            logger.info("{} recalled last pile in room {}", sessionObj.playerName, message.roomCode)
            
        } catch (e: Exception) {
            sendError(session, e.message ?: "Failed to recall pile", ErrorType.TRANSIENT, ErrorCode.CONFLICT, message.opId)
        }
    }
    
    private suspend fun handleRecallLastDiscard(session: WebSocketSession, message: WebSocketMessage.RecallLastDiscard) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
        // idempotency check
        message.opId?.let { opId ->
            getCachedAck(sessionObj.sessionId, opId)?.let { cached ->
                session.send(cached)
                return
            }
        }
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
            return
        }

        try {
            roomService.executeRoomOperation(message.roomCode) {
                room.atomicUpdate {
                    val updatedGameState = gameService.recallLastDiscard(room, sessionObj.playerName).getOrThrow()
                    val updatedRoom = room.copy(gameState = updatedGameState)
                    roomService.updateRoom(message.roomCode, updatedRoom)
                }
            }
            
            val updatedRoom = roomService.getRoom(message.roomCode)!!
            broadcastGameStateUpdate(message.roomCode, updatedRoom)
            
            sendSuccess(session, "Last discard recalled!", message.opId)
            logger.info("{} recalled last discard in room {}", sessionObj.playerName, message.roomCode)
            
        } catch (e: Exception) {
            sendError(session, e.message ?: "Failed to recall discard", ErrorType.TRANSIENT, ErrorCode.CONFLICT, message.opId)
        }
    }
    
    private suspend fun handleSortHand(session: WebSocketSession, message: WebSocketMessage.SortHand) {
        try {
            val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
            // idempotency check
            message.opId?.let { opId ->
                getCachedAck(sessionObj.sessionId, opId)?.let { cached ->
                    session.send(cached)
                    return
                }
            }
            if (!ValidationService.validateRoomCode(message.roomCode)) {
                sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
                return
            }

            val room = roomService.getRoom(message.roomCode)

            if (room == null) {
                sendError(session, "Room not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND, message.opId)
                return
            }

            val player = room.players[sessionObj.playerName]
            if (player == null) {
                sendError(session, "Player not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
                return
            }

            val sortedHand = gameService.sortHand(player.hand, message.sortBy)
            val updatedRoom = room.copy(players = room.players.toMutableMap().apply {
                this[sessionObj.playerName] = player.copy(hand = sortedHand)
            })
            roomService.updateRoom(message.roomCode, updatedRoom)

            broadcastGameStateUpdate(message.roomCode, updatedRoom)

            val sortType = if (message.sortBy == SortType.RANK) "rank" else "suit"
            logger.info("Player {} sorted hand by {} in room {}", sessionObj.playerName, sortType, message.roomCode)
        } catch (e: Exception) {
            logger.error("Error sorting hand for room {}: {}", message.roomCode, e.message, e)
            sendError(session, e.message ?: "Failed to sort hand", ErrorType.TRANSIENT, ErrorCode.CONFLICT, message.opId)
        }
    }
    
    private suspend fun handleReorderHand(session: WebSocketSession, message: WebSocketMessage.ReorderHand) {
        try {
            val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
            // idempotency check
            message.opId?.let { opId ->
                getCachedAck(sessionObj.sessionId, opId)?.let { cached ->
                    session.send(cached)
                    return
                }
            }

            // Validate input
            if (!ValidationService.validateCardIds(message.cardIds)) {
                sendError(session, "Invalid card IDs", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
                return
            }
            if (!ValidationService.validateRoomCode(message.roomCode)) {
                sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
                return
            }

            val room = roomService.getRoom(message.roomCode)

            if (room == null) {
                sendError(session, "Room not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
                return
            }

            val player = room.players[sessionObj.playerName]
            if (player == null) {
                sendError(session, "Player not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
                return
            }

            val reorderedHand = message.cardIds.mapNotNull { cardId ->
                player.hand.find { it.id == cardId }
            }

            if (reorderedHand.size != message.cardIds.size) {
                sendError(session, "Invalid card IDs", ErrorType.TRANSIENT, ErrorCode.VALIDATION)
                return
            }

            val updatedRoom = room.copy(players = room.players.toMutableMap().apply {
                this[sessionObj.playerName] = player.copy(hand = reorderedHand)
            })
            roomService.updateRoom(message.roomCode, updatedRoom)
            broadcastGameStateUpdate(message.roomCode, updatedRoom)

            logger.info("Player {} reordered hand in room {}", sessionObj.playerName, message.roomCode)
        } catch (e: Exception) {
            logger.error("Error reordering hand for room {}: {}", message.roomCode, e.message, e)
            sendError(session, e.message ?: "Failed to reorder hand", ErrorType.TRANSIENT, ErrorCode.CONFLICT, message.opId)
        }
    }

    private suspend fun handleRestartGame(session: WebSocketSession, message: WebSocketMessage.RestartGame) {
        try {
            val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
            // idempotency check
            message.opId?.let { opId ->
                getCachedAck(sessionObj.sessionId, opId)?.let { cached ->
                    session.send(cached)
                    return
                }
            }
            if (!ValidationService.validateRoomCode(message.roomCode)) {
                sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
                return
            }

            val room = roomService.getRoom(message.roomCode)

            if (room == null) {
                sendError(session, "Room not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
                return
            }

            // Reset game state - create new room with updated state
            val resetGameState = GameState(
                deck = emptyList(),
                table = emptyList(),
                discardPile = emptyList()
            )

            // Clear all player hands
            val clearedPlayers = room.players.mapValues { (_, player) ->
                player.copy(hand = emptyList())
            }

            val updatedRoom = room.copy(
                gameState = resetGameState,
                state = RoomState.WAITING,
                players = clearedPlayers.toMutableMap()
            )

            // Update the room in the service
            roomService.updateRoom(message.roomCode, updatedRoom)

            broadcastGameStateUpdate(message.roomCode, updatedRoom)

            sendSuccess(session, "Game restarted!", message.opId)

            logger.info("Player {} restarted game in room {}", sessionObj.playerName, message.roomCode)
        } catch (e: Exception) {
            logger.error("Error restarting game for room {}: {}", message.roomCode, e.message, e)
            sendError(session, e.message ?: "Failed to restart game", ErrorType.TRANSIENT, ErrorCode.CONFLICT, message.opId)
        }
    }

    private suspend fun handleReconnect(session: WebSocketSession, message: WebSocketMessage.Reconnect) {
        try {
            // Validate input
            if (!ValidationService.validatePlayerName(message.playerName)) {
                sendError(session, "Invalid player name", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
                return
            }
            if (!ValidationService.validateRoomCode(message.roomCode)) {
                sendError(session, "Invalid room code", ErrorType.TRANSIENT, ErrorCode.VALIDATION, message.opId)
                return
            }

            val room = roomService.getRoom(message.roomCode)

            if (room == null) {
                sendError(session, "Room not found", ErrorType.CRITICAL, ErrorCode.NOT_FOUND)
                return
            }

            val player = room.players[message.playerName]
            if (player == null) {
                sendError(session, "Player not in room", ErrorType.CRITICAL, ErrorCode.NOT_FOUND, message.opId)
                return
            }

            // Validate and restore session by sessionId
            val existingSession = sessions[message.sessionId]
            if (existingSession == null) {
                sendError(session, "Invalid session", ErrorType.CRITICAL, ErrorCode.NOT_FOUND, message.opId)
                return
            }
            if (existingSession.playerName != message.playerName || existingSession.roomCode != message.roomCode) {
                sendError(session, "Session does not match player/room", ErrorType.CRITICAL, ErrorCode.AUTHZ, message.opId)
                return
            }

            sessions[message.sessionId] = existingSession.withUpdatedActivity()
            sessionToWebSocket[message.sessionId] = session
            // Cancel pending removal if any, and mark presence true
            pendingDisconnectRemoval.remove(message.sessionId)?.cancel()
            try {
                roomService.executeRoomOperation(message.roomCode) {
                    room.atomicUpdate {
                        val p = room.players[message.playerName]
                        if (p != null && !p.isConnected) {
                            val updatedRoom = room.copy(players = room.players.toMutableMap().apply {
                                this[message.playerName] = p.copy(isConnected = true)
                            })
                            roomService.updateRoom(message.roomCode, updatedRoom)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("Error marking player {} connected in room {}: {}", message.playerName, message.roomCode, e.message)
            }

            connections.computeIfAbsent(message.roomCode) { mutableSetOf() }.add(session)

            // Send current game state to reconnected player
            val gameStateUpdate = WebSocketMessage.GameStateUpdate(
                roomCode = message.roomCode,
                gameState = room.gameState,
                players = room.players
            )
            session.send(json.encodeToString(WebSocketMessage.serializer(), gameStateUpdate))

            sendSuccess(session, "Reconnected to room ${message.roomCode}!", message.opId)
            // Broadcast presence update to others
            roomService.getRoom(message.roomCode)?.let { updated ->
                broadcastGameStateUpdate(message.roomCode, updated)
            }

            logger.info("Player {} reconnected to room {}", message.playerName, message.roomCode)
        } catch (e: Exception) {
            logger.error("Error reconnecting player {} to room {}: {}", message.playerName, message.roomCode, e.message, e)
            sendError(session, e.message ?: "Failed to reconnect", ErrorType.CRITICAL, ErrorCode.UNKNOWN, message.opId)
        }
    }

    private suspend fun broadcastGameStateUpdate(roomCode: String, room: Room) {
        val gameStateUpdate = WebSocketMessage.GameStateUpdate(
            roomCode = roomCode,
            gameState = room.gameState,
            players = room.players
        )
        broadcastToRoom(roomCode, gameStateUpdate)
    }
    
    private suspend fun broadcastToRoom(roomCode: String, message: WebSocketMessage, excludeSession: WebSocketSession? = null) {
        val roomConnections = connections[roomCode] ?: return

        val messageText = json.encodeToString(WebSocketMessage.serializer(), message)

        roomConnections.forEach { session ->
            if (session != excludeSession && session.isActive) {
                sendWithRetry(session, messageText, roomCode)
            }
        }
    }

    private suspend fun sendWithRetry(session: WebSocketSession, messageText: String, roomCode: String) {
        var attempt = 0
        val maxRetries = ServerConfig.SEND_RETRY_MAX_ATTEMPTS
        while (attempt < maxRetries) {
            try {
                session.send(messageText)
                return // Success
            } catch (e: Exception) {
                attempt++
                if (attempt < maxRetries) {
                    val delayMs = (ServerConfig.SEND_RETRY_BASE_MS * (1 shl attempt))
                    delay(delayMs)
                } else {
                    // Add to dead letter queue
                    deadLetterQueue.computeIfAbsent(roomCode) { mutableListOf() }.add(messageText)
                    MetricsService.onDLQAdd(roomCode)
                    logger.warn("Broadcast failed after {} attempts for room {}, message added to dead letter queue: {}", maxRetries, roomCode, e.message)
                }
            }
        }
    }
    
    private suspend fun sendError(session: WebSocketSession, message: String, type: ErrorType, code: ErrorCode? = null, opId: String? = null) {
        MetricsService.onError(code)
        val errorMessage = WebSocketMessage.Error(message, type, code, opId)
        val text = json.encodeToString(WebSocketMessage.serializer(), errorMessage)
        session.send(text)
        val sessionObj = getSessionFromWebSocket(session)
        if (sessionObj != null && opId != null) {
            cacheAck(sessionObj.sessionId, opId, text)
        }
    }

    private suspend fun sendSuccess(session: WebSocketSession, message: String, opId: String? = null) {
        val successMessage = WebSocketMessage.Success(message, opId)
        val text = json.encodeToString(WebSocketMessage.serializer(), successMessage)
        session.send(text)
        val sessionObj = getSessionFromWebSocket(session)
        if (sessionObj != null && opId != null) {
            cacheAck(sessionObj.sessionId, opId, text)
        }
    }
    
    private fun getSessionFromWebSocket(session: WebSocketSession): Session? {
        return sessionToWebSocket.entries.find { it.value == session }?.key?.let { sessions[it] }
    }
    
    private fun getCachedAck(sessionId: String, opId: String): String? {
        val map = opCache[sessionId] ?: return null
        return map[opId]
    }
    
    private fun cacheAck(sessionId: String, opId: String, ackJson: String) {
        val map = opCache.computeIfAbsent(sessionId) { createOpMap() }
        map[opId] = ackJson
    }
    
    private fun createOpMap(): MutableMap<String, String> {
        return Collections.synchronizedMap(object: LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return this.size > ServerConfig.OP_ID_LRU_MAX
            }
        })
    }
    
    private fun cleanupDisconnectedPlayer(session: WebSocketSession) {
        // Find which session this WebSocket belongs to
        val sessionId = sessionToWebSocket.entries.find { it.value == session }?.key
        if (sessionId != null) {
            val sessionObj = sessions[sessionId]
            if (sessionObj != null) {
                // Remove from connection tracking only - keep session for potential reconnection
                connections[sessionObj.roomCode]?.remove(session)
                sessionToWebSocket.remove(sessionId)

                // Mark presence = false and broadcast updated state
                val roomCode = sessionObj.roomCode
                val room = roomService.getRoom(roomCode)
                if (room != null) {
                    try {
                        roomService.executeRoomOperation(roomCode) {
                            room.atomicUpdate {
                                val p = room.players[sessionObj.playerName]
                                if (p != null && p.isConnected) {
                                    val updatedRoom = room.copy(players = room.players.toMutableMap().apply {
                                        this[sessionObj.playerName] = p.copy(isConnected = false)
                                    })
                                    roomService.updateRoom(roomCode, updatedRoom)
                                }
                            }
                        }
                        roomService.getRoom(roomCode)?.let { updated ->
                            CoroutineScope(Dispatchers.Default).launch {
                                broadcastGameStateUpdate(roomCode, updated)
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("Error marking player {} disconnected in room {}: {}", sessionObj.playerName, roomCode, e.message)
                    }

                    // Schedule removal after grace period if not reconnected
                    val job = CoroutineScope(Dispatchers.Default).launch {
                        delay(disconnectGraceMs)
                        // If reconnected, abort
                        if (sessionToWebSocket.containsKey(sessionId)) return@launch
                        val roomNow = roomService.getRoom(roomCode) ?: return@launch
                        var oldHost: String? = null
                        var newHost: String? = null
                        try {
                            roomService.executeRoomOperation(roomCode) {
                                roomNow.atomicUpdate {
                                    oldHost = roomNow.players.values.find { it.isHost }?.name
                                    val wasInRoom = roomNow.players.containsKey(sessionObj.playerName)
                                    if (wasInRoom) {
                                        roomNow.removePlayer(sessionObj.playerName)
                                        newHost = roomNow.players.values.find { it.isHost }?.name
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            logger.error("Error finalizing disconnect for {} in room {}: {}", sessionObj.playerName, roomCode, e.message)
                        }
                        val includeHost = (oldHost != null && oldHost != newHost)
                        val playerLeft = WebSocketMessage.PlayerLeft(
                            roomCode = roomCode,
                            playerName = sessionObj.playerName,
                            newHost = if (includeHost) newHost else null
                        )
                        broadcastToRoom(roomCode, playerLeft, excludeSession = null)
                        logger.info("Player {} removed from room {} after grace window", sessionObj.playerName, roomCode)
                    }
                    pendingDisconnectRemoval[sessionId] = job
                }
            }
        }
    }
    
    private fun cleanupExpiredSessions() {
        val expired = sessions.filterValues { it.isExpired(ServerConfig.SESSION_TTL_HOURS) }.toMap()
        expired.forEach { (sessionId, sessionObj) ->
            val ws = sessionToWebSocket.remove(sessionId)
            sessions.remove(sessionId)
            MetricsService.onSessionRemoved()
            opCache.remove(sessionId)
            if (ws != null) {
                connections[sessionObj.roomCode]?.remove(ws)
            }
            logger.info("Cleaned up expired session for player {} in room {}", sessionObj.playerName, sessionObj.roomCode)
        }
    }

    private fun isMessageRateLimited(sessionId: String): Boolean {
        val now = System.currentTimeMillis()
        val timestamps = messageTimestamps.getOrPut(sessionId) { ArrayDeque() }
        while (timestamps.isNotEmpty() && timestamps.first < now - ServerConfig.MESSAGE_RATE_WINDOW_MS) {
            timestamps.removeFirst()
        }
        if (timestamps.size >= ServerConfig.MESSAGE_RATE_MAX_PER_WINDOW) {
            return true
        }
        timestamps.addLast(now)
        return false
    }

    private fun getRemoteHost(session: WebSocketSession): String? {
        return if (session is DefaultWebSocketServerSession) {
            val req = session.call.request
            val forwarded = req.headers["X-Forwarded-For"]?.split(',')?.firstOrNull()?.trim()
            forwarded ?: req.local.remoteHost
        } else null
    }
}
