package com.example.cardsnow

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

private val json = Json {
    classDiscriminator = "messageType"
}

data class GameState(
    val screen: String = "splash",
    val roomCode: String = "",
    val playerName: String = "",
    val numDecks: Int = 1,
    val includeJokers: Boolean = false,
    val hostName: String = "Host",
    val players: List<String> = emptyList(),
    val isHost: Boolean = false,
    val gameStarted: Boolean = false,
    val myHand: List<Card> = emptyList(),
    val table: List<List<Card>> = emptyList(),
    val discardPile: List<Card> = emptyList(),
    val selectedCards: Map<String, Boolean> = emptyMap(),
    val deckEmpty: Boolean = false,
    val deckSize: Int = 0,
    val otherPlayersHandSizes: Map<String, Int> = emptyMap(),
    val lastPlayedPlayer: String = "",
    val lastPlayedCardIds: List<String> = emptyList(),
    val lastDiscardedPlayer: String = "",
    val lastDiscardedCardIds: List<String> = emptyList(),
    val canRecall: Boolean = false,
    val showMenu: Boolean = false,
    val isLoadingGeneral: Boolean = false,
    val isPlayingCards: Boolean = false,
    val isDrawingCard: Boolean = false,
    val errorMessage: String = "",
    val errorType: ErrorType = ErrorType.NONE,
    val successMessage: String = "",
    val isConnected: Boolean = false,
    val showNewHostDialog: Boolean = false
)

enum class ErrorType {
    NONE, TRANSIENT, CRITICAL
}

class KtorViewModel : ViewModel() {

    private val client = HttpClient(CIO) {
        install(WebSockets)
        engine {
            requestTimeout = 30000
        }
    }
    
    private var webSocketSession: WebSocketSession? = null
    private var reconnectJob: Job? = null
    
    private val _gameState = mutableStateOf(GameState())
    val gameState: State<GameState> = _gameState
    
    private val cardResourceMap = mapOf(
        "Spades_Ace" to R.drawable.ace_of_spades, "Spades_2" to R.drawable.two_of_spades,
        "Spades_3" to R.drawable.three_of_spades, "Spades_4" to R.drawable.four_of_spades,
        "Spades_5" to R.drawable.five_of_spades, "Spades_6" to R.drawable.six_of_spades,
        "Spades_7" to R.drawable.seven_of_spades, "Spades_8" to R.drawable.eight_of_spades,
        "Spades_9" to R.drawable.nine_of_spades, "Spades_10" to R.drawable.ten_of_spades,
        "Spades_Jack" to R.drawable.jack_of_spades, "Spades_Queen" to R.drawable.queen_of_spades,
        "Spades_King" to R.drawable.king_of_spades,
        "Hearts_Ace" to R.drawable.ace_of_hearts, "Hearts_2" to R.drawable.two_of_hearts,
        "Hearts_3" to R.drawable.three_of_hearts, "Hearts_4" to R.drawable.four_of_hearts,
        "Hearts_5" to R.drawable.five_of_hearts, "Hearts_6" to R.drawable.six_of_hearts,
        "Hearts_7" to R.drawable.seven_of_hearts, "Hearts_8" to R.drawable.eight_of_hearts,
        "Hearts_9" to R.drawable.nine_of_hearts, "Hearts_10" to R.drawable.ten_of_hearts,
        "Hearts_Jack" to R.drawable.jack_of_hearts, "Hearts_Queen" to R.drawable.queen_of_hearts,
        "Hearts_King" to R.drawable.king_of_hearts,
        "Clubs_Ace" to R.drawable.ace_of_clubs, "Clubs_2" to R.drawable.two_of_clubs,
        "Clubs_3" to R.drawable.three_of_clubs, "Clubs_4" to R.drawable.four_of_clubs,
        "Clubs_5" to R.drawable.five_of_clubs, "Clubs_6" to R.drawable.six_of_clubs,
        "Clubs_7" to R.drawable.seven_of_clubs, "Clubs_8" to R.drawable.eight_of_clubs,
        "Clubs_9" to R.drawable.nine_of_clubs, "Clubs_10" to R.drawable.ten_of_clubs,
        "Clubs_Jack" to R.drawable.jack_of_clubs, "Clubs_Queen" to R.drawable.queen_of_clubs,
        "Clubs_King" to R.drawable.king_of_clubs,
        "Diamonds_Ace" to R.drawable.ace_of_diamonds, "Diamonds_2" to R.drawable.two_of_diamonds,
        "Diamonds_3" to R.drawable.three_of_diamonds, "Diamonds_4" to R.drawable.four_of_diamonds,
        "Diamonds_5" to R.drawable.five_of_diamonds, "Diamonds_6" to R.drawable.six_of_diamonds,
        "Diamonds_7" to R.drawable.seven_of_diamonds, "Diamonds_8" to R.drawable.eight_of_diamonds,
        "Diamonds_9" to R.drawable.nine_of_diamonds, "Diamonds_10" to R.drawable.ten_of_diamonds,
        "Diamonds_Jack" to R.drawable.jack_of_diamonds, "Diamonds_Queen" to R.drawable.queen_of_diamonds,
        "Diamonds_King" to R.drawable.king_of_diamonds,
        "Joker_Red" to R.drawable.red_joker, "Joker_Black" to R.drawable.black_joker
    )

    fun connectToServer() {
        viewModelScope.launch {
            _gameState.value = _gameState.value.copy(isLoadingGeneral = true)
            try {
                client.webSocket("ws://10.0.2.2:8080/ws") {
                    webSocketSession = this
                    _gameState.value = _gameState.value.copy(
                        isConnected = true,
                        isLoadingGeneral = false,
                        errorMessage = "",
                        errorType = ErrorType.NONE
                    )
                    
                    // Start listening for messages
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            handleIncomingMessage(frame.readText())
                        }
                    }
                }
            } catch (e: Exception) {
                _gameState.value = _gameState.value.copy(
                    isConnected = false,
                    isLoadingGeneral = false,
                    errorMessage = "Connection failed: ${e.message}",
                    errorType = ErrorType.CRITICAL
                )
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(5000) // Wait 5 seconds before reconnecting
            if (!_gameState.value.isConnected) {
                connectToServer()
            }
        }
    }

    private fun handleIncomingMessage(message: String) {
        try {
            println("Received WebSocket message: $message")
            
            // Parse the base message to determine type
            val jsonElement = json.parseToJsonElement(message)
            val jsonObject = jsonElement.jsonObject
            val messageType = jsonObject["messageType"]?.jsonPrimitive?.content ?: return
            
            when (messageType) {
                "success" -> {
                    val successMessage = jsonObject["message"]?.jsonPrimitive?.content ?: ""
                    handleSuccess(successMessage)
                }
                "error" -> {
                    val errorMessage = jsonObject["message"]?.jsonPrimitive?.content ?: ""
                    val errorTypeStr = jsonObject["errorType"]?.jsonPrimitive?.content ?: "TRANSIENT"
                    val errorType = ErrorType.valueOf(errorTypeStr)
                    _gameState.value = _gameState.value.copy(isLoadingGeneral = false)
                    showError(errorMessage, errorType)
                }
                "game_state_update" -> {
                    handleGameStateUpdate(jsonObject)
                }
                "player_joined" -> {
                    val players = jsonObject["players"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                    handlePlayerJoined(players)
                }
                "player_left" -> {
                    val playerName = jsonObject["playerName"]?.jsonPrimitive?.content ?: ""
                    val newHost = jsonObject["newHost"]?.jsonPrimitive?.content
                    handlePlayerLeft(playerName, newHost)
                }
                "room_created" -> {
                    val roomCode = jsonObject["roomCode"]?.jsonPrimitive?.content ?: ""
                    val players = jsonObject["players"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                    _gameState.value = _gameState.value.copy(
                        roomCode = roomCode,
                        players = players
                    )
                }
            }
        } catch (e: Exception) {
            println("Error parsing message: ${e.message}")
            showError("Invalid server message", ErrorType.TRANSIENT)
        }
    }

    private fun handleSuccess(message: String) {
        // Check if this is a room creation success message
        if (message.startsWith("Room ") && message.endsWith(" created!")) {
            val roomCode = message.substring(5, message.length - 9).trim()
            _gameState.value = _gameState.value.copy(
                roomCode = roomCode,
                players = listOf(_gameState.value.playerName), // Add host to players list
                screen = "room",
                successMessage = message,
                errorMessage = "",
                errorType = ErrorType.NONE,
                isLoadingGeneral = false
            )
        } else if (message.contains(" room ") && message.endsWith("!")) {
            // For join or rejoin success
            _gameState.value = _gameState.value.copy(
                screen = "room",
                successMessage = message,
                errorMessage = "",
                errorType = ErrorType.NONE,
                isLoadingGeneral = false
            )
        } else {
            _gameState.value = _gameState.value.copy(
                successMessage = message,
                errorMessage = "",
                errorType = ErrorType.NONE
            )
        }

        // Clear success message after delay
        viewModelScope.launch {
            delay(2000)
            if (_gameState.value.successMessage == message) {
                _gameState.value = _gameState.value.copy(successMessage = "")
            }
        }
    }

    private fun handleGameStateUpdate(jsonObject: kotlinx.serialization.json.JsonObject) {
        try {
            val roomCode = jsonObject["roomCode"]?.jsonPrimitive?.content ?: return
            
            // Parse game state
            val gameStateJson = jsonObject["gameState"]?.jsonObject
            val deckSize = gameStateJson?.get("deck")?.jsonArray?.size ?: 0
            val table = parseTable(gameStateJson?.get("table")?.jsonArray)
            val discardPile = parseCards(gameStateJson?.get("discardPile")?.jsonArray)
            val lastPlayedJson = gameStateJson?.get("lastPlayed")?.jsonObject
            val lastPlayedPlayer = lastPlayedJson?.get("player")?.jsonPrimitive?.content ?: ""
            val lastPlayedCardIds = lastPlayedJson?.get("cardIds")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val lastDiscardedJson = gameStateJson?.get("lastDiscarded")?.jsonObject
            val lastDiscardedPlayer = lastDiscardedJson?.get("player")?.jsonPrimitive?.content ?: ""
            val lastDiscardedCardIds = lastDiscardedJson?.get("cardIds")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            
            // Parse players
            val playersJson = jsonObject["players"]?.jsonObject
            val players = playersJson?.keys?.toList() ?: emptyList()
            
            // Find current player
            val currentPlayer = playersJson?.entries?.find { 
                it.value.jsonObject["name"]?.jsonPrimitive?.content == _gameState.value.playerName 
            }
            
            val isHost = currentPlayer?.value?.jsonObject?.get("isHost")?.jsonPrimitive?.booleanOrNull ?: false
            val myHand = parseCards(currentPlayer?.value?.jsonObject?.get("hand")?.jsonArray)
            
            // Calculate other players' hand sizes
            val otherPlayersHandSizes = mutableMapOf<String, Int>()
            playersJson?.entries?.forEach { entry ->
                val playerName = entry.value.jsonObject["name"]?.jsonPrimitive?.content
                if (playerName != _gameState.value.playerName) {
                    val handSize = entry.value.jsonObject["hand"]?.jsonArray?.size ?: 0
                    if (playerName != null) {
                        otherPlayersHandSizes[playerName] = handSize
                    }
                }
            }
            val lastPileCardIds = if (table.isNotEmpty()) table.last().map { it.id } else emptyList()
            val pileEligible = lastPlayedPlayer == _gameState.value.playerName &&
                    lastPileCardIds.isNotEmpty() &&
                    lastPileCardIds == lastPlayedCardIds
            val discardEligible = lastDiscardedPlayer == _gameState.value.playerName &&
                    lastDiscardedCardIds.isNotEmpty() &&
                    discardPile.size >= lastDiscardedCardIds.size &&
                    discardPile.takeLast(lastDiscardedCardIds.size).map { it.id } == lastDiscardedCardIds
            val canRecall = pileEligible || discardEligible
            
            _gameState.value = _gameState.value.copy(
                roomCode = roomCode,
                players = players,
                isHost = isHost,
                gameStarted = true,
                myHand = myHand,
                table = table,
                discardPile = discardPile,
                deckSize = deckSize,
                deckEmpty = deckSize == 0,
                otherPlayersHandSizes = otherPlayersHandSizes,
                lastPlayedPlayer = lastPlayedPlayer,
                lastPlayedCardIds = lastPlayedCardIds,
                lastDiscardedPlayer = lastDiscardedPlayer,
                lastDiscardedCardIds = lastDiscardedCardIds,
                canRecall = canRecall,
                isLoadingGeneral = false
            )
        } catch (e: Exception) {
            println("Error handling game state update: ${e.message}")
            showError("Failed to update game state", ErrorType.TRANSIENT)
        }
    }

    private fun parseTable(tableJson: kotlinx.serialization.json.JsonArray?): List<List<Card>> {
        if (tableJson == null) return emptyList()
        
        return tableJson.map { pileElement ->
            parseCards(pileElement.jsonArray)
        }
    }

    private fun parseCards(cardsJson: kotlinx.serialization.json.JsonArray?): List<Card> {
        if (cardsJson == null) return emptyList()
        
        return cardsJson.mapNotNull { cardElement ->
            try {
                val cardObj = cardElement.jsonObject
                val suit = cardObj["suit"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val rank = cardObj["rank"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val id = cardObj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
                
                val resourceId = cardResourceMap["${suit}_$rank"] ?: R.drawable.card_back_red
                Card(suit, rank, resourceId, id)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun handlePlayerJoined(players: List<String>) {
        _gameState.value = _gameState.value.copy(
            players = players
        )
    }

    private fun handlePlayerLeft(playerName: String, newHost: String?) {
        val updatedPlayers = _gameState.value.players - playerName
        val isNowHost = newHost == _gameState.value.playerName

        _gameState.value = _gameState.value.copy(
            players = updatedPlayers,
            isHost = isNowHost,
            showNewHostDialog = isNowHost && !_gameState.value.isHost
        )
    }

    // Navigation methods
    fun navigateToHome() {
        _gameState.value = _gameState.value.copy(screen = "home")
    }

    fun navigateToCreate() {
        _gameState.value = _gameState.value.copy(screen = "create")
    }

    fun navigateToJoin() {
        _gameState.value = _gameState.value.copy(screen = "join")
    }

    // Room creation and joining
    fun createRoom() {
        viewModelScope.launch {
            _gameState.value = _gameState.value.copy(isLoadingGeneral = true)
            
            val settings = RoomSettings(
                numDecks = _gameState.value.numDecks,
                includeJokers = _gameState.value.includeJokers,
                dealCount = 0
            )
            
            val message = WebSocketMessage.CreateRoom(settings, _gameState.value.hostName.trim())
            sendMessage(message)
            
            _gameState.value = _gameState.value.copy(
                playerName = _gameState.value.hostName.trim(),
                isHost = true
            )
        }
    }

    fun joinRoom(roomCode: String, playerName: String) {
        viewModelScope.launch {
            _gameState.value = _gameState.value.copy(isLoadingGeneral = true)
            
            val trimmedCode = roomCode.trim()
            val trimmedName = playerName.trim()
            
            if (trimmedName.isBlank()) {
                showError("Please enter a name!", ErrorType.TRANSIENT)
                _gameState.value = _gameState.value.copy(isLoadingGeneral = false)
                return@launch
            }
            
            if (trimmedCode.isBlank() || !trimmedCode.matches(Regex("\\d{4}"))) {
                showError("Room code must be a 4-digit number!", ErrorType.TRANSIENT)
                _gameState.value = _gameState.value.copy(isLoadingGeneral = false)
                return@launch
            }
            
            val message = WebSocketMessage.JoinRoom(trimmedCode, trimmedName)
            sendMessage(message)
            
            _gameState.value = _gameState.value.copy(
                roomCode = trimmedCode,
                playerName = trimmedName
            )
        }
    }

    // Game actions
    fun startGame() {
        viewModelScope.launch {
            _gameState.value = _gameState.value.copy(isLoadingGeneral = true)
            
            val roomCode = _gameState.value.roomCode
            if (roomCode.isNotEmpty()) {
                val message = WebSocketMessage.StartGame(roomCode)
                sendMessage(message)
            }
            
            _gameState.value = _gameState.value.copy(isLoadingGeneral = false)
        }
    }

    fun toggleCardSelection(card: Card) {
        _gameState.value = _gameState.value.copy(
            selectedCards = if (_gameState.value.selectedCards[card.id] == true) {
                _gameState.value.selectedCards - card.id
            } else {
                _gameState.value.selectedCards + (card.id to true)
            }
        )
    }

    fun playCards() {
        val cardsToPlay = _gameState.value.myHand.filter { 
            _gameState.value.selectedCards[it.id] == true 
        }
        
        if (cardsToPlay.isEmpty()) {
            showError("No cards selected to play!", ErrorType.TRANSIENT)
            return
        }
        
        viewModelScope.launch {
            _gameState.value = _gameState.value.copy(isPlayingCards = true)
            
            val message = WebSocketMessage.PlayCards(
                roomCode = _gameState.value.roomCode,
                playerName = _gameState.value.playerName,
                cardIds = cardsToPlay.map { it.id }
            )
            sendMessage(message)
            
            _gameState.value = _gameState.value.copy(
                selectedCards = emptyMap(),
                isPlayingCards = false
            )
        }
    }

    fun discardCards() {
        val cardsToDiscard = _gameState.value.myHand.filter { 
            _gameState.value.selectedCards[it.id] == true 
        }
        
        if (cardsToDiscard.isEmpty()) {
            showError("No cards selected to discard!", ErrorType.TRANSIENT)
            return
        }
        
        viewModelScope.launch {
            _gameState.value = _gameState.value.copy(isPlayingCards = true)
            
            val message = WebSocketMessage.DiscardCards(
                roomCode = _gameState.value.roomCode,
                playerName = _gameState.value.playerName,
                cardIds = cardsToDiscard.map { it.id }
            )
            sendMessage(message)
            
            _gameState.value = _gameState.value.copy(
                selectedCards = emptyMap(),
                isPlayingCards = false
            )
        }
    }

    fun drawCard() {
        viewModelScope.launch {
            _gameState.value = _gameState.value.copy(isDrawingCard = true)
            
            val message = WebSocketMessage.DrawCard(
                roomCode = _gameState.value.roomCode,
                playerName = _gameState.value.playerName
            )
            sendMessage(message)
            
            _gameState.value = _gameState.value.copy(isDrawingCard = false)
        }
    }

    fun drawFromDiscard() {
        viewModelScope.launch {
            _gameState.value = _gameState.value.copy(isDrawingCard = true)
            
            val message = WebSocketMessage.DrawFromDiscard(
                roomCode = _gameState.value.roomCode,
                playerName = _gameState.value.playerName
            )
            sendMessage(message)
            
            _gameState.value = _gameState.value.copy(isDrawingCard = false)
        }
    }

    fun shuffleDeck() {
        viewModelScope.launch {
            _gameState.value = _gameState.value.copy(isLoadingGeneral = true)
            
            val message = WebSocketMessage.ShuffleDeck(
                roomCode = _gameState.value.roomCode,
                playerName = _gameState.value.playerName
            )
            sendMessage(message)
            
            _gameState.value = _gameState.value.copy(isLoadingGeneral = false)
        }
    }

    fun dealDeck(count: Int) {
        viewModelScope.launch {
            _gameState.value = _gameState.value.copy(isLoadingGeneral = true)
            
            val message = WebSocketMessage.DealCards(
                roomCode = _gameState.value.roomCode,
                playerName = _gameState.value.playerName,
                count = count
            )
            sendMessage(message)
            
            _gameState.value = _gameState.value.copy(isLoadingGeneral = false)
        }
    }

    fun moveCardsToPlayer(targetPlayer: String) {
        val cardsToMove = _gameState.value.myHand.filter { 
            _gameState.value.selectedCards[it.id] == true 
        }
        
        if (cardsToMove.isEmpty()) {
            showError("No cards selected to move!", ErrorType.TRANSIENT)
            return
        }
        
        viewModelScope.launch {
            _gameState.value = _gameState.value.copy(isLoadingGeneral = true)
            
            val message = WebSocketMessage.MoveCards(
                roomCode = _gameState.value.roomCode,
                fromPlayer = _gameState.value.playerName,
                toPlayer = targetPlayer,
                cardIds = cardsToMove.map { it.id }
            )
            sendMessage(message)
            
            _gameState.value = _gameState.value.copy(
                selectedCards = emptyMap(),
                isLoadingGeneral = false
            )
        }
    }

    fun recallLastPile() {
        viewModelScope.launch {
            _gameState.value = _gameState.value.copy(isLoadingGeneral = true)
            
            val pileEligible = _gameState.value.lastPlayedPlayer == _gameState.value.playerName &&
                    _gameState.value.table.isNotEmpty() &&
                    _gameState.value.table.last().map { it.id } == _gameState.value.lastPlayedCardIds
            val discardEligible = _gameState.value.lastDiscardedPlayer == _gameState.value.playerName &&
                    _gameState.value.lastDiscardedCardIds.isNotEmpty() &&
                    _gameState.value.discardPile.size >= _gameState.value.lastDiscardedCardIds.size &&
                    _gameState.value.discardPile.takeLast(_gameState.value.lastDiscardedCardIds.size).map { it.id } == _gameState.value.lastDiscardedCardIds

            if (discardEligible) {
                val message = WebSocketMessage.RecallLastDiscard(
                    roomCode = _gameState.value.roomCode,
                    playerName = _gameState.value.playerName
                )
                sendMessage(message)
            } else if (pileEligible) {
                val message = WebSocketMessage.RecallLastPile(
                    roomCode = _gameState.value.roomCode,
                    playerName = _gameState.value.playerName
                )
                sendMessage(message)
            } else {
                showError("Nothing to recall", ErrorType.TRANSIENT)
            }
            
            _gameState.value = _gameState.value.copy(isLoadingGeneral = false)
        }
    }

    fun sortByRank() {
        val sortedHand = CardGameLogic.sortByRank(_gameState.value.myHand)
        updateHandOrder(sortedHand.map { it.id })
    }

    fun sortBySuit() {
        val sortedHand = CardGameLogic.sortBySuit(_gameState.value.myHand)
        updateHandOrder(sortedHand.map { it.id })
    }

    fun reorderHand(newOrder: List<Card>) {
        _gameState.value = _gameState.value.copy(myHand = newOrder)
        updateHandOrder(newOrder.map { it.id })
    }

    private fun updateHandOrder(cardIds: List<String>) {
        viewModelScope.launch {
            val message = WebSocketMessage.ReorderHand(
                roomCode = _gameState.value.roomCode,
                playerName = _gameState.value.playerName,
                cardIds = cardIds
            )
            sendMessage(message)
        }
    }

    fun refreshPlayers() {
        // This method is called from GameRoomScreen but doesn't need to do anything
        // since player updates are handled via WebSocket messages
        println("refreshPlayers called - players are updated via WebSocket")
    }

    fun restartGame() {
        viewModelScope.launch {
            _gameState.value = _gameState.value.copy(isLoadingGeneral = true)
            
            val message = WebSocketMessage.RestartGame(
                roomCode = _gameState.value.roomCode,
                playerName = _gameState.value.playerName
            )
            sendMessage(message)
            
            _gameState.value = _gameState.value.copy(isLoadingGeneral = false)
        }
    }

    // Utility methods
    fun showError(message: String, type: ErrorType = ErrorType.TRANSIENT) {
        _gameState.value = _gameState.value.copy(errorMessage = message, errorType = type)
        
        if (type == ErrorType.TRANSIENT) {
            viewModelScope.launch {
                delay(3000)
                clearError()
            }
        }
    }

    fun clearError() {
        _gameState.value = _gameState.value.copy(errorMessage = "", errorType = ErrorType.NONE)
    }

    fun clearSuccess() {
        _gameState.value = _gameState.value.copy(successMessage = "")
    }

    fun clearNewHostDialog() {
        _gameState.value = _gameState.value.copy(showNewHostDialog = false)
    }

    fun toggleMenu() {
        _gameState.value = _gameState.value.copy(showMenu = !_gameState.value.showMenu)
    }

    fun leaveRoom() {
        viewModelScope.launch {
            webSocketSession?.close()
            webSocketSession = null
            _gameState.value = GameState(screen = "home")
        }
    }

    fun exitGame(activity: MainActivity) {
        viewModelScope.launch {
            webSocketSession?.close()
            webSocketSession = null
            activity.finishAffinity()
        }
    }

    // Settings
    fun setHostName(newHostName: String) {
        _gameState.value = _gameState.value.copy(hostName = newHostName)
    }

    fun setNumDecks(newNumDecks: Int) {
        _gameState.value = _gameState.value.copy(numDecks = newNumDecks)
    }

    fun setIncludeJokers(newIncludeJokers: Boolean) {
        _gameState.value = _gameState.value.copy(includeJokers = newIncludeJokers)
    }

    private fun sendMessage(message: WebSocketMessage) {
        viewModelScope.launch {
            try {
                val jsonString = json.encodeToString(message)
                println("Sending message: $jsonString")
                webSocketSession?.send(Frame.Text(jsonString))
            } catch (e: Exception) {
                println("Error sending message: ${e.message}")
                showError("Failed to send message: ${e.message}", ErrorType.TRANSIENT)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        reconnectJob?.cancel()
        viewModelScope.launch {
            webSocketSession?.close()
            client.close()
        }
    }
}