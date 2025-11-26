package com.cardsnow.backend.services

import com.cardsnow.backend.models.*
import com.cardsnow.backend.models.ErrorType
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.isActive
import com.cardsnow.backend.plugins.json
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

class ConnectionService(
    private val roomService: RoomService,
    private val gameService: GameService
) {
    private val connections = ConcurrentHashMap<String, MutableSet<WebSocketSession>>()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val sessionToWebSocket = ConcurrentHashMap<String, WebSocketSession>()
    
    init {
        // Start session cleanup job
        CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                delay(5 * 60 * 1000L) // Run every 5 minutes
                cleanupExpiredSessions()
            }
        }
    }
    
    suspend fun handleConnection(session: WebSocketSession) {
        try {
            session.incoming.consumeEach { frame ->
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    handleMessage(session, text)
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            println("WebSocket connection closed: ${e.message}")
        } catch (e: Exception) {
            println("WebSocket error: ${e.message}")
        } finally {
            // Clean up disconnected player
            cleanupDisconnectedPlayer(session)
        }
    }
    
    private suspend fun handleMessage(session: WebSocketSession, message: String) {
        try {
            val webSocketMessage = json.decodeFromString<WebSocketMessage>(message)
            
            when (webSocketMessage) {
                is WebSocketMessage.CreateRoom -> {
                    handleCreateRoom(session, webSocketMessage)
                }
                is WebSocketMessage.JoinRoom -> {
                    handleJoinRoom(session, webSocketMessage)
                }
                is WebSocketMessage.StartGame -> {
                    handleStartGame(session, webSocketMessage)
                }
                is WebSocketMessage.PlayCards -> {
                    handlePlayCards(session, webSocketMessage)
                }
                is WebSocketMessage.DiscardCards -> {
                    handleDiscardCards(session, webSocketMessage)
                }
                is WebSocketMessage.DrawCard -> {
                    handleDrawCard(session, webSocketMessage)
                }
                is WebSocketMessage.DrawFromDiscard -> {
                    handleDrawFromDiscard(session, webSocketMessage)
                }
                is WebSocketMessage.ShuffleDeck -> {
                    handleShuffleDeck(session, webSocketMessage)
                }
                is WebSocketMessage.DealCards -> {
                    handleDealCards(session, webSocketMessage)
                }
                is WebSocketMessage.MoveCards -> {
                    handleMoveCards(session, webSocketMessage)
                }
                is WebSocketMessage.RecallLastPile -> {
                    handleRecallLastPile(session, webSocketMessage)
                }
                is WebSocketMessage.RecallLastDiscard -> {
                    handleRecallLastDiscard(session, webSocketMessage)
                }
                is WebSocketMessage.SortHand -> {
                    handleSortHand(session, webSocketMessage)
                }
                is WebSocketMessage.ReorderHand -> {
                    handleReorderHand(session, webSocketMessage)
                }
                is WebSocketMessage.RestartGame -> {
                    handleRestartGame(session, webSocketMessage)
                }
                is WebSocketMessage.Reconnect -> {
                    handleReconnect(session, webSocketMessage)
                }
                else -> {
                    // Unknown message type
                    sendError(session, "Unknown message type", ErrorType.CRITICAL)
                }
            }
        } catch (e: Exception) {
            println("Error handling message: ${e.message}")
            sendError(session, "Invalid message format", ErrorType.CRITICAL)
        }
    }
    
    private suspend fun handleCreateRoom(session: WebSocketSession, message: WebSocketMessage.CreateRoom) {
        // Validate input
        if (!ValidationService.validatePlayerName(message.hostName)) {
            sendError(session, "Invalid player name. Must be 1-20 alphanumeric characters.", ErrorType.TRANSIENT)
            return
        }
        if (!ValidationService.validateRoomSettings(message.settings)) {
            sendError(session, "Invalid room settings.", ErrorType.TRANSIENT)
            return
        }
        
        val roomCode = roomService.createRoom(message.settings, message.hostName)
        
        // Create session for the host
        val sessionObj = Session.create(message.hostName, roomCode)
        sessions[sessionObj.sessionId] = sessionObj
        sessionToWebSocket[sessionObj.sessionId] = session
        
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

        // Send success message to trigger frontend navigation
        val successMessage = WebSocketMessage.Success("Room $roomCode created!")
        session.send(json.encodeToString(WebSocketMessage.serializer(), successMessage))

        println("Room created: $roomCode by ${message.hostName} with session ${sessionObj.sessionId}")
    }
    
    private suspend fun handleJoinRoom(session: WebSocketSession, message: WebSocketMessage.JoinRoom) {
        // Validate input
        if (!ValidationService.validatePlayerName(message.playerName)) {
            sendError(session, "Invalid player name. Must be 1-20 alphanumeric characters.", ErrorType.TRANSIENT)
            return
        }
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
            return
        }

        val isRejoining = room.players.containsKey(message.playerName)

        if (isRejoining) {
            // Check if player is still connected
            val existingSession = sessions.values.find { it.playerName == message.playerName && it.roomCode == message.roomCode }
            if (existingSession != null && sessionToWebSocket.containsKey(existingSession.sessionId)) {
                sendError(session, "Player name already taken.", ErrorType.TRANSIENT)
                return
            }
            
            // Player is rejoining, restore session mapping
            if (existingSession != null) {
                sessions[existingSession.sessionId] = existingSession.withUpdatedActivity()
                sessionToWebSocket[existingSession.sessionId] = session
            }
            
            connections.computeIfAbsent(message.roomCode) { mutableSetOf() }.add(session)

            // Send current game state to reconnected player
            val gameStateUpdate = WebSocketMessage.GameStateUpdate(
                roomCode = message.roomCode,
                gameState = room.gameState,
                players = room.players
            )
            session.send(json.encodeToString(WebSocketMessage.serializer(), gameStateUpdate))

            val successMessage = WebSocketMessage.Success("Rejoined room ${message.roomCode} as ${message.playerName}!")
            session.send(json.encodeToString(WebSocketMessage.serializer(), successMessage))

            println("Player ${message.playerName} rejoined room ${message.roomCode}")
        } else {
            // New player joining
            if (room.players.containsKey(message.playerName)) {
                sendError(session, "Player name already taken.", ErrorType.TRANSIENT)
                return
            }
            
            val success = roomService.joinRoom(message.roomCode, message.playerName)

            if (success) {
                // Create session for new player
                val sessionObj = Session.create(message.playerName, message.roomCode)
                sessions[sessionObj.sessionId] = sessionObj
                sessionToWebSocket[sessionObj.sessionId] = session
                
                // Add connection tracking
                connections.computeIfAbsent(message.roomCode) { mutableSetOf() }.add(session)

                // Send success response
                val successMessage = WebSocketMessage.Success("Joined room ${message.roomCode} as ${message.playerName}!")
                session.send(json.encodeToString(WebSocketMessage.serializer(), successMessage))
                
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

                println("Player ${message.playerName} joined room ${message.roomCode}")
            } else {
                sendError(session, "Failed to join room", ErrorType.CRITICAL)
            }
        }
    }
    
    private suspend fun handleStartGame(session: WebSocketSession, message: WebSocketMessage.StartGame) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL)
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
            return
        }

        val player = room.players.values.find { it.isHost }
        if (player?.name != sessionObj.playerName) {
            sendError(session, "Only host can start game", ErrorType.TRANSIENT)
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

            val successMessage = WebSocketMessage.Success("Game started!")
            session.send(json.encodeToString(WebSocketMessage.serializer(), successMessage))
            println("Game started in room ${message.roomCode}")
        } catch (e: Exception) {
            sendError(session, e.message ?: "Failed to start game", ErrorType.TRANSIENT)
        }
    }
    
    private suspend fun handlePlayCards(session: WebSocketSession, message: WebSocketMessage.PlayCards) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL)
        
        // Validate input
        if (!ValidationService.validateCardIds(message.cardIds)) {
            sendError(session, "Invalid card IDs", ErrorType.TRANSIENT)
            return
        }
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
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
            println("${sessionObj.playerName} played ${message.cardIds.size} cards in room ${message.roomCode}")
            
        } catch (e: Exception) {
            sendError(session, e.message ?: "Failed to play cards", ErrorType.TRANSIENT)
        }
    }
    
    private suspend fun handleDiscardCards(session: WebSocketSession, message: WebSocketMessage.DiscardCards) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL)
        
        // Validate input
        if (!ValidationService.validateCardIds(message.cardIds)) {
            sendError(session, "Invalid card IDs", ErrorType.TRANSIENT)
            return
        }
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
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
            println("${sessionObj.playerName} discarded ${message.cardIds.size} cards in room ${message.roomCode}")
        } catch (e: Exception) {
            sendError(session, e.message ?: "Failed to discard cards", ErrorType.TRANSIENT)
        }
    }
    
    private suspend fun handleDrawCard(session: WebSocketSession, message: WebSocketMessage.DrawCard) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL)
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
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
            println("${sessionObj.playerName} drew a card in room ${message.roomCode}")
            
        } catch (e: Exception) {
            sendError(session, e.message ?: "Failed to draw card", ErrorType.TRANSIENT)
        }
    }
    
    private suspend fun handleDrawFromDiscard(session: WebSocketSession, message: WebSocketMessage.DrawFromDiscard) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL)
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
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
            println("${sessionObj.playerName} drew from discard in room ${message.roomCode}")
            
        } catch (e: Exception) {
            sendError(session, e.message ?: "Failed to draw from discard", ErrorType.TRANSIENT)
        }
    }
    
    private suspend fun handleShuffleDeck(session: WebSocketSession, message: WebSocketMessage.ShuffleDeck) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL)
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
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
            
            val successMessage = WebSocketMessage.Success("Deck shuffled!")
            session.send(json.encodeToString(WebSocketMessage.serializer(), successMessage))
            println("${sessionObj.playerName} shuffled deck in room ${message.roomCode}")
            
        } catch (e: Exception) {
            sendError(session, e.message ?: "Failed to shuffle deck", ErrorType.TRANSIENT)
        }
    }
    
    private suspend fun handleDealCards(session: WebSocketSession, message: WebSocketMessage.DealCards) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL)
        
        // Validate input
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
            return
        }

        // Validate card count based on deck size and player count
        val playerCount = room.players.size
        if (message.count <= 0 || message.count * playerCount > room.gameState.deck.size) {
            sendError(session, "Invalid card count. Must be between 1 and ${room.gameState.deck.size / playerCount}.", ErrorType.TRANSIENT)
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
            
            val successMessage = WebSocketMessage.Success("Dealt ${message.count} cards to each player!")
            session.send(json.encodeToString(WebSocketMessage.serializer(), successMessage))
            println("${sessionObj.playerName} dealt ${message.count} cards in room ${message.roomCode}")
            
        } catch (e: Exception) {
            sendError(session, e.message ?: "Failed to deal cards", ErrorType.TRANSIENT)
        }
    }
    
    private suspend fun handleMoveCards(session: WebSocketSession, message: WebSocketMessage.MoveCards) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL)
        
        // Validate input
        if (!ValidationService.validatePlayerName(message.toPlayer)) {
            sendError(session, "Invalid target player name", ErrorType.TRANSIENT)
            return
        }
        if (!ValidationService.validateCardIds(message.cardIds)) {
            sendError(session, "Invalid card IDs", ErrorType.TRANSIENT)
            return
        }
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
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
            println("${sessionObj.playerName} moved cards to ${message.toPlayer} in room ${message.roomCode}")
            
        } catch (e: Exception) {
            sendError(session, e.message ?: "Failed to move cards", ErrorType.TRANSIENT)
        }
    }
    
    private suspend fun handleRecallLastPile(session: WebSocketSession, message: WebSocketMessage.RecallLastPile) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL)
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
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
            
            val successMessage = WebSocketMessage.Success("Last pile recalled!")
            session.send(json.encodeToString(WebSocketMessage.serializer(), successMessage))
            println("${sessionObj.playerName} recalled last pile in room ${message.roomCode}")
            
        } catch (e: Exception) {
            sendError(session, e.message ?: "Failed to recall pile", ErrorType.TRANSIENT)
        }
    }
    
    private suspend fun handleRecallLastDiscard(session: WebSocketSession, message: WebSocketMessage.RecallLastDiscard) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL)
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
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
            
            val successMessage = WebSocketMessage.Success("Last discard recalled!")
            session.send(json.encodeToString(WebSocketMessage.serializer(), successMessage))
            println("${sessionObj.playerName} recalled last discard in room ${message.roomCode}")
            
        } catch (e: Exception) {
            sendError(session, e.message ?: "Failed to recall discard", ErrorType.TRANSIENT)
        }
    }
    
    private suspend fun handleSortHand(session: WebSocketSession, message: WebSocketMessage.SortHand) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL)
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
            return
        }

        val player = room.players[sessionObj.playerName]
        if (player == null) {
            sendError(session, "Player not found", ErrorType.CRITICAL)
            return
        }

        val sortedHand = gameService.sortHand(player.hand, message.sortBy)
        val updatedRoom = room.copy(players = room.players.toMutableMap().apply {
            this[sessionObj.playerName] = player.copy(hand = sortedHand)
        })
        roomService.updateRoom(message.roomCode, updatedRoom)

        broadcastGameStateUpdate(message.roomCode, updatedRoom)

        val sortType = if (message.sortBy == SortType.RANK) "rank" else "suit"
        println("${sessionObj.playerName} sorted hand by $sortType in room ${message.roomCode}")
    }
    
    private suspend fun handleReorderHand(session: WebSocketSession, message: WebSocketMessage.ReorderHand) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL)
        
        // Validate input
        if (!ValidationService.validateCardIds(message.cardIds)) {
            sendError(session, "Invalid card IDs", ErrorType.TRANSIENT)
            return
        }
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
            return
        }

        val player = room.players[sessionObj.playerName]
        if (player == null) {
            sendError(session, "Player not found", ErrorType.CRITICAL)
            return
        }

        val reorderedHand = message.cardIds.mapNotNull { cardId ->
            player.hand.find { it.id == cardId }
        }

        if (reorderedHand.size != message.cardIds.size) {
            sendError(session, "Invalid card IDs", ErrorType.TRANSIENT)
            return
        }

        val updatedRoom = room.copy(players = room.players.toMutableMap().apply {
            this[sessionObj.playerName] = player.copy(hand = reorderedHand)
        })
        roomService.updateRoom(message.roomCode, updatedRoom)
        broadcastGameStateUpdate(message.roomCode, updatedRoom)

        println("${sessionObj.playerName} reordered hand in room ${message.roomCode}")
    }

    private suspend fun handleRestartGame(session: WebSocketSession, message: WebSocketMessage.RestartGame) {
        val sessionObj = getSessionFromWebSocket(session) ?: return sendError(session, "Session not found", ErrorType.CRITICAL)
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code. Must be 4 digits.", ErrorType.TRANSIENT)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
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

        val successMessage = WebSocketMessage.Success("Game restarted!")
        session.send(json.encodeToString(WebSocketMessage.serializer(), successMessage))

        println("${sessionObj.playerName} restarted game in room ${message.roomCode}")
    }

    private suspend fun handleReconnect(session: WebSocketSession, message: WebSocketMessage.Reconnect) {
        // Validate input
        if (!ValidationService.validatePlayerName(message.playerName)) {
            sendError(session, "Invalid player name", ErrorType.TRANSIENT)
            return
        }
        if (!ValidationService.validateRoomCode(message.roomCode)) {
            sendError(session, "Invalid room code", ErrorType.TRANSIENT)
            return
        }
        
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
            return
        }

        val player = room.players[message.playerName]
        if (player == null) {
            sendError(session, "Player not in room", ErrorType.CRITICAL)
            return
        }

        // Restore session mapping
        val existingSession = sessions.values.find { it.playerName == message.playerName && it.roomCode == message.roomCode }
        if (existingSession != null) {
            sessions[existingSession.sessionId] = existingSession.withUpdatedActivity()
            sessionToWebSocket[existingSession.sessionId] = session
        }
        
        connections.computeIfAbsent(message.roomCode) { mutableSetOf() }.add(session)

        // Send current game state to reconnected player
        val gameStateUpdate = WebSocketMessage.GameStateUpdate(
            roomCode = message.roomCode,
            gameState = room.gameState,
            players = room.players
        )
        session.send(json.encodeToString(WebSocketMessage.serializer(), gameStateUpdate))

        val successMessage = WebSocketMessage.Success("Reconnected to room ${message.roomCode}!")
        session.send(json.encodeToString(WebSocketMessage.serializer(), successMessage))

        println("Player ${message.playerName} reconnected to room ${message.roomCode}")
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
                try {
                    session.send(messageText)
                } catch (e: Exception) {
                    println("Failed to send message to session: ${e.message}")
                }
            }
        }
    }
    
    private suspend fun sendError(session: WebSocketSession, message: String, type: ErrorType) {
        val errorMessage = WebSocketMessage.Error(message, type)
        session.send(json.encodeToString(WebSocketMessage.serializer(), errorMessage))
    }
    
    private fun getSessionFromWebSocket(session: WebSocketSession): Session? {
        return sessionToWebSocket.entries.find { it.value == session }?.key?.let { sessions[it] }
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

                println("Player ${sessionObj.playerName} disconnected from room ${sessionObj.roomCode} (session cleaned up, session remains active)")
            }
        }
    }
    
    private fun cleanupExpiredSessions() {
        val now = System.currentTimeMillis()
        val expiredSessions = sessions.filter { (_, session) ->
            session.isExpired()
        }
        
        expiredSessions.forEach { (sessionId, session) ->
            val ws = sessionToWebSocket[sessionId]
            sessions.remove(sessionId)
            sessionToWebSocket.remove(sessionId)
            if (ws != null) {
                connections[session.roomCode]?.remove(ws)
            }
            println("Cleaned up expired session for player ${session.playerName} in room ${session.roomCode}")
        }
    }
}
