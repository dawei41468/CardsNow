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

class ConnectionService(
    private val roomService: RoomService,
    private val gameService: GameService
) {
    private val connections = ConcurrentHashMap<String, MutableSet<WebSocketSession>>()
    private val playerConnections = ConcurrentHashMap<String, WebSocketSession>()
    
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
        val roomCode = roomService.createRoom(message.settings, message.hostName)
        
        // Add connection tracking
        connections.computeIfAbsent(roomCode) { mutableSetOf() }.add(session)
        playerConnections["${roomCode}_${message.hostName}"] = session
        
        // Send room created message
        val roomCreatedMessage = WebSocketMessage.RoomCreated(
            roomCode = roomCode,
            players = listOf(message.hostName)
        )
        session.send(json.encodeToString(WebSocketMessage.serializer(), roomCreatedMessage))

        println("Room created: $roomCode by ${message.hostName}")
    }
    
    private suspend fun handleJoinRoom(session: WebSocketSession, message: WebSocketMessage.JoinRoom) {
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
            return
        }

        val isRejoining = room.players.containsKey(message.playerName)

        if (isRejoining) {
            // Player is rejoining, just restore session mapping
            connections.computeIfAbsent(message.roomCode) { mutableSetOf() }.add(session)
            playerConnections["${message.roomCode}_${message.playerName}"] = session

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
            val success = roomService.joinRoom(message.roomCode, message.playerName)

            if (success) {
                // Add connection tracking
                connections.computeIfAbsent(message.roomCode) { mutableSetOf() }.add(session)
                playerConnections["${message.roomCode}_${message.playerName}"] = session

                // Send success response
                val successMessage = WebSocketMessage.Success("Joined room ${message.roomCode} as ${message.playerName}!")
                session.send(json.encodeToString(WebSocketMessage.serializer(), successMessage))

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
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
            return
        }

        val player = room.players.values.find { it.isHost }
        if (player?.name != getPlayerNameFromSession(session, message.roomCode)) {
            sendError(session, "Only host can start game", ErrorType.TRANSIENT)
            return
        }

        val result = gameService.startGame(room, room.players.keys.toList())

        result.fold(
            onSuccess = { gameState ->
                val updatedRoom = room.copy(gameState = gameState)
                roomService.updateRoom(message.roomCode, updatedRoom)

                // Broadcast game state update to all room members
                val gameStateUpdate = WebSocketMessage.GameStateUpdate(
                    roomCode = message.roomCode,
                    gameState = gameState,
                    players = updatedRoom.players
                )
                broadcastToRoom(message.roomCode, gameStateUpdate)

                val successMessage = WebSocketMessage.Success("Game started!")
                session.send(json.encodeToString(WebSocketMessage.serializer(), successMessage))

                println("Game started in room ${message.roomCode}")
            },
            onFailure = { error ->
                sendError(session, error.message ?: "Failed to start game", ErrorType.TRANSIENT)
            }
        )
    }
    
    private suspend fun handlePlayCards(session: WebSocketSession, message: WebSocketMessage.PlayCards) {
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
            return
        }

        val playerName = getPlayerNameFromSession(session, message.roomCode) ?: return sendError(session, "Player not authenticated", ErrorType.CRITICAL)

        val result = gameService.playCards(room, playerName, message.cardIds)

        result.fold(
            onSuccess = { updatedGameState ->
                val updatedRoom = room.copy(gameState = updatedGameState)
                roomService.updateRoom(message.roomCode, updatedRoom)
                broadcastGameStateUpdate(message.roomCode, updatedRoom)

                println("$playerName played ${message.cardIds.size} cards in room ${message.roomCode}")
            },
            onFailure = { error ->
                sendError(session, error.message ?: "Failed to play cards", ErrorType.TRANSIENT)
            }
        )
    }
    
    private suspend fun handleDiscardCards(session: WebSocketSession, message: WebSocketMessage.DiscardCards) {
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
            return
        }

        val playerName = getPlayerNameFromSession(session, message.roomCode) ?: return sendError(session, "Player not authenticated", ErrorType.CRITICAL)

        val result = gameService.discardCards(room, playerName, message.cardIds)

        result.fold(
            onSuccess = { updatedGameState ->
                val updatedRoom = room.copy(gameState = updatedGameState)
                roomService.updateRoom(message.roomCode, updatedRoom)
                broadcastGameStateUpdate(message.roomCode, updatedRoom)

                println("$playerName discarded ${message.cardIds.size} cards in room ${message.roomCode}")
            },
            onFailure = { error ->
                sendError(session, error.message ?: "Failed to discard cards", ErrorType.TRANSIENT)
            }
        )
    }
    
    private suspend fun handleDrawCard(session: WebSocketSession, message: WebSocketMessage.DrawCard) {
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
            return
        }

        val playerName = getPlayerNameFromSession(session, message.roomCode) ?: return sendError(session, "Player not authenticated", ErrorType.CRITICAL)

        val result = gameService.drawCard(room, playerName)

        result.fold(
            onSuccess = { (card, updatedGameState) ->
                val updatedRoom = room.copy(gameState = updatedGameState)
                roomService.updateRoom(message.roomCode, updatedRoom)
                broadcastGameStateUpdate(message.roomCode, updatedRoom)

                println("$playerName drew a card in room ${message.roomCode}")
            },
            onFailure = { error ->
                sendError(session, error.message ?: "Failed to draw card", ErrorType.TRANSIENT)
            }
        )
    }
    
    private suspend fun handleDrawFromDiscard(session: WebSocketSession, message: WebSocketMessage.DrawFromDiscard) {
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
            return
        }

        val playerName = getPlayerNameFromSession(session, message.roomCode) ?: return sendError(session, "Player not authenticated", ErrorType.CRITICAL)

        val result = gameService.drawFromDiscard(room, playerName)

        result.fold(
            onSuccess = { updatedGameState ->
                val updatedRoom = room.copy(gameState = updatedGameState)
                roomService.updateRoom(message.roomCode, updatedRoom)
                broadcastGameStateUpdate(message.roomCode, updatedRoom)

                println("$playerName drew from discard in room ${message.roomCode}")
            },
            onFailure = { error ->
                sendError(session, error.message ?: "Failed to draw from discard", ErrorType.TRANSIENT)
            }
        )
    }
    
    private suspend fun handleShuffleDeck(session: WebSocketSession, message: WebSocketMessage.ShuffleDeck) {
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
            return
        }

        val playerName = getPlayerNameFromSession(session, message.roomCode) ?: return sendError(session, "Player not authenticated", ErrorType.CRITICAL)

        val result = gameService.shuffleDeck(room, playerName)

        result.fold(
            onSuccess = { updatedGameState ->
                val updatedRoom = room.copy(gameState = updatedGameState)
                roomService.updateRoom(message.roomCode, updatedRoom)
                broadcastGameStateUpdate(message.roomCode, updatedRoom)

                val successMessage = WebSocketMessage.Success("Deck shuffled!")
                session.send(json.encodeToString(WebSocketMessage.serializer(), successMessage))

                println("$playerName shuffled deck in room ${message.roomCode}")
            },
            onFailure = { error ->
                sendError(session, error.message ?: "Failed to shuffle deck", ErrorType.TRANSIENT)
            }
        )
    }
    
    private suspend fun handleDealCards(session: WebSocketSession, message: WebSocketMessage.DealCards) {
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
            return
        }

        val playerName = getPlayerNameFromSession(session, message.roomCode) ?: return sendError(session, "Player not authenticated", ErrorType.CRITICAL)

        val result = gameService.dealCards(room, playerName, message.count)

        result.fold(
            onSuccess = { (playerHands, updatedGameState) ->
                val updatedRoom = room.copy(gameState = updatedGameState)
                roomService.updateRoom(message.roomCode, updatedRoom)
                broadcastGameStateUpdate(message.roomCode, updatedRoom)

                val successMessage = WebSocketMessage.Success("Dealt ${message.count} cards to each player!")
                session.send(json.encodeToString(WebSocketMessage.serializer(), successMessage))

                println("$playerName dealt ${message.count} cards in room ${message.roomCode}")
            },
            onFailure = { error ->
                sendError(session, error.message ?: "Failed to deal cards", ErrorType.TRANSIENT)
            }
        )
    }
    
    private suspend fun handleMoveCards(session: WebSocketSession, message: WebSocketMessage.MoveCards) {
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
            return
        }

        val fromPlayer = getPlayerNameFromSession(session, message.roomCode) ?: return sendError(session, "Player not authenticated", ErrorType.CRITICAL)

        val result = gameService.moveCards(room, fromPlayer, message.toPlayer, message.cardIds)

        result.fold(
            onSuccess = { updatedGameState ->
                val updatedRoom = room.copy(gameState = updatedGameState)
                roomService.updateRoom(message.roomCode, updatedRoom)
                broadcastGameStateUpdate(message.roomCode, updatedRoom)

                println("$fromPlayer moved cards to ${message.toPlayer} in room ${message.roomCode}")
            },
            onFailure = { error ->
                sendError(session, error.message ?: "Failed to move cards", ErrorType.TRANSIENT)
            }
        )
    }
    
    private suspend fun handleRecallLastPile(session: WebSocketSession, message: WebSocketMessage.RecallLastPile) {
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
            return
        }

        val playerName = getPlayerNameFromSession(session, message.roomCode) ?: return sendError(session, "Player not authenticated", ErrorType.CRITICAL)

        val result = gameService.recallLastPile(room, playerName)

        result.fold(
            onSuccess = { updatedGameState ->
                val updatedRoom = room.copy(gameState = updatedGameState)
                roomService.updateRoom(message.roomCode, updatedRoom)
                broadcastGameStateUpdate(message.roomCode, updatedRoom)

                val successMessage = WebSocketMessage.Success("Last pile recalled!")
                session.send(json.encodeToString(WebSocketMessage.serializer(), successMessage))

                println("$playerName recalled last pile in room ${message.roomCode}")
            },
            onFailure = { error ->
                sendError(session, error.message ?: "Failed to recall pile", ErrorType.TRANSIENT)
            }
        )
    }
    
    private suspend fun handleSortHand(session: WebSocketSession, message: WebSocketMessage.SortHand) {
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
            return
        }

        val playerName = getPlayerNameFromSession(session, message.roomCode) ?: return sendError(session, "Player not authenticated", ErrorType.CRITICAL)

        val player = room.players[playerName]
        if (player == null) {
            sendError(session, "Player not found", ErrorType.CRITICAL)
            return
        }

        val sortedHand = gameService.sortHand(player.hand, message.sortBy)
        room.players[playerName] = player.copy(hand = sortedHand)

        broadcastGameStateUpdate(message.roomCode, room)

        val sortType = if (message.sortBy == SortType.RANK) "rank" else "suit"
        println("$playerName sorted hand by $sortType in room ${message.roomCode}")
    }
    
    private suspend fun handleReorderHand(session: WebSocketSession, message: WebSocketMessage.ReorderHand) {
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
            return
        }

        val playerName = getPlayerNameFromSession(session, message.roomCode) ?: return sendError(session, "Player not authenticated", ErrorType.CRITICAL)

        val player = room.players[playerName]
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

        room.players[playerName] = player.copy(hand = reorderedHand)
        broadcastGameStateUpdate(message.roomCode, room)

        println("$playerName reordered hand in room ${message.roomCode}")
    }

    private suspend fun handleRestartGame(session: WebSocketSession, message: WebSocketMessage.RestartGame) {
        val room = roomService.getRoom(message.roomCode)

        if (room == null) {
            sendError(session, "Room not found", ErrorType.CRITICAL)
            return
        }

        val playerName = getPlayerNameFromSession(session, message.roomCode) ?: return sendError(session, "Player not authenticated", ErrorType.CRITICAL)

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

        println("$playerName restarted game in room ${message.roomCode}")
    }

    private suspend fun handleReconnect(session: WebSocketSession, message: WebSocketMessage.Reconnect) {
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
        connections.computeIfAbsent(message.roomCode) { mutableSetOf() }.add(session)
        playerConnections["${message.roomCode}_${message.playerName}"] = session

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
    
    private fun getPlayerNameFromSession(session: WebSocketSession, roomCode: String): String? {
        return playerConnections.entries.find { (key, value) ->
            key.startsWith("${roomCode}_") && value == session
        }?.key?.removePrefix("${roomCode}_")
    }
    
    private fun cleanupDisconnectedPlayer(session: WebSocketSession) {
        // Find which player this session belongs to
        val entry = playerConnections.entries.find { it.value == session }
        if (entry != null) {
            val (key, _) = entry
            val parts = key.split("_")
            if (parts.size == 2) {
                val roomCode = parts[0]
                val playerName = parts[1]

                // Remove from connection tracking only - keep player in room for potential reconnection
                playerConnections.remove(key)
                connections[roomCode]?.remove(session)

                println("Player $playerName disconnected from room $roomCode (session cleaned up, player remains in room)")
            }
        }
    }
}