package com.example.cardsnow

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.example.cardsnow.wire.WebSocketMessage as WireMessage
import com.example.cardsnow.wire.RoomSettings as WireRoomSettings
import kotlin.random.Random
import com.example.cardsnow.ws.WsClient

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
    val showNewHostDialog: Boolean = false,
    val lastVersion: Long = 0
)

enum class ErrorType {
    NONE, TRANSIENT, CRITICAL
}

class KtorViewModel : ViewModel() {

    private val client = HttpClient(CIO) {
        install(WebSockets)
        engine {
            requestTimeout = ClientConfig.REQUEST_TIMEOUT_MS
        }
    }
    
    private var webSocketSession: WebSocketSession? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts: Int = 0
    private var sessionId: String? = null
    private var pingJob: Job? = null
    private val outgoingQueue: ArrayDeque<WireMessage> = ArrayDeque()
    private var wsClient: WsClient = WsClient.Session { webSocketSession }
    private var flushAllowed: Boolean = false
    
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
                client.webSocket(ClientConfig.WS_URL) {
                    webSocketSession = this
                    _gameState.value = _gameState.value.copy(
                        isConnected = true,
                        isLoadingGeneral = false,
                        errorMessage = "",
                        errorType = ErrorType.NONE
                    )
                    reconnectAttempts = 0
                    flushAllowed = false
                    pingJob?.cancel()
                    pingJob = viewModelScope.launch {
                        while (_gameState.value.isConnected) {
                            delay(ClientConfig.PING_INTERVAL_MS)
                            runCatching { wsClient.send(Frame.Ping(byteArrayOf())) }
                                .onFailure { if (ClientConfig.IS_DEBUG) Log.d(TAG, "Ping failed: ${it.message}") }
                        }
                    }
                    if (_gameState.value.roomCode.isNotBlank() &&
                        _gameState.value.playerName.isNotBlank() &&
                        sessionId != null
                    ) {
                        val message = WireMessage.Reconnect(
                            roomCode = _gameState.value.roomCode,
                            playerName = _gameState.value.playerName,
                            sessionId = sessionId!!
                        )
                        sendMessage(message)
                    }
                    
                    // Start listening for messages
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            handleIncomingMessage(frame.readText())
                        }
                    }
                    _gameState.value = _gameState.value.copy(isConnected = false)
                    pingJob?.cancel()
                    scheduleReconnect()
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
        val attempt = (++reconnectAttempts).coerceAtLeast(1)
        val backoff = ClientConfig.RECONNECT_BACKOFF_BASE_MS * (1L shl attempt.coerceAtMost(ClientConfig.RECONNECT_BACKOFF_MAX_SHIFT))
        val delayMs = minOf(backoff, ClientConfig.RECONNECT_BACKOFF_MAX_MS) + Random.nextLong(0, ClientConfig.RECONNECT_JITTER_MAX_MS + 1)
        reconnectJob = viewModelScope.launch {
            delay(delayMs)
            if (!_gameState.value.isConnected) {
                connectToServer()
            }
        }
    }

    private fun handleIncomingMessage(message: String) {
        try {
            if (ClientConfig.IS_DEBUG) Log.d(TAG, "Received WebSocket message: $message")
            when (val wsMessage = json.decodeFromString<WireMessage>(message)) {
                is WireMessage.Success -> handleSuccess(wsMessage.message)
                is WireMessage.Error -> {
                    _gameState.value = _gameState.value.copy(isLoadingGeneral = false)
                    val type = runCatching { ErrorType.valueOf(wsMessage.type.name) }
                        .getOrDefault(ErrorType.TRANSIENT)
                    val friendly = when (wsMessage.code) {
                        com.example.cardsnow.wire.ErrorCode.RATE_LIMITED -> "You're sending too fast. Please wait a moment."
                        com.example.cardsnow.wire.ErrorCode.PAYLOAD_TOO_LARGE -> "Message too large."
                        com.example.cardsnow.wire.ErrorCode.TIMEOUT -> "Operation timed out. Please try again."
                        com.example.cardsnow.wire.ErrorCode.INVALID_FORMAT -> "Invalid request."
                        com.example.cardsnow.wire.ErrorCode.UNKNOWN, null -> wsMessage.message
                    }
                    showError(friendly, type)
                }
                is WireMessage.GameStateUpdate -> handleGameStateUpdate(wsMessage)
                is WireMessage.PlayerJoined -> handlePlayerJoined(wsMessage.players)
                is WireMessage.PlayerLeft -> handlePlayerLeft(wsMessage.playerName, wsMessage.newHost)
                is WireMessage.RoomCreated -> {
                    _gameState.value = _gameState.value.copy(
                        roomCode = wsMessage.roomCode,
                        players = wsMessage.players,
                        screen = "room",
                        isLoadingGeneral = false
                    )
                }
                is WireMessage.SessionCreated -> {
                    if (wsMessage.sessionId.isNotBlank()) {
                        sessionId = wsMessage.sessionId
                    }
                    _gameState.value = _gameState.value.copy(
                        screen = "room",
                        isLoadingGeneral = false
                    )
                    flushAllowed = true
                    flushOutgoingQueue()
                }
                else -> {
                    // Messages handled elsewhere or not needed by client
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}")
            showError("Invalid server message", ErrorType.TRANSIENT)
        }
    }

    private fun handleGameStateUpdate(message: WireMessage.GameStateUpdate) {
        try {
            val roomCode = message.roomCode
            val serverState = message.gameState
            val version = serverState.version

            // Ignore only strictly older updates unless this looks like a full reset
            val isResetState = serverState.deck.isEmpty() &&
                    serverState.discardPile.isEmpty() &&
                    (serverState.table.isEmpty() || serverState.table.all { it.isEmpty() }) &&
                    message.players.values.all { it.hand.isEmpty() }
            if (version < _gameState.value.lastVersion && !isResetState) {
                if (ClientConfig.IS_DEBUG) Log.d(TAG, "Ignoring older game state update: version $version < ${_gameState.value.lastVersion}")
                return
            }

            val deckSize = serverState.deck.size
            val table = serverState.table.map { pile ->
                pile.map { cardDto ->
                    val resourceId = cardResourceMap["${cardDto.suit}_${cardDto.rank}"] ?: R.drawable.card_back_red
                    Card(cardDto.suit, cardDto.rank, resourceId, cardDto.id)
                }
            }
            val discardPile = serverState.discardPile.map { cardDto ->
                val resourceId = cardResourceMap["${cardDto.suit}_${cardDto.rank}"] ?: R.drawable.card_back_red
                Card(cardDto.suit, cardDto.rank, resourceId, cardDto.id)
            }
            val lastPlayedPlayer = serverState.lastPlayed.player
            val lastPlayedCardIds = serverState.lastPlayed.cardIds
            val lastDiscardedPlayer = serverState.lastDiscarded.player
            val lastDiscardedCardIds = serverState.lastDiscarded.cardIds

            // Basic client-side validation
            if (deckSize < 0) {
                Log.w(TAG, "Invalid game state: negative deck size $deckSize")
                showError("Invalid game state received", ErrorType.CRITICAL)
                return
            }
            if (table.any { it.isEmpty() }) {
                Log.w(TAG, "Invalid game state: empty pile in table")
                showError("Invalid game state received", ErrorType.CRITICAL)
                return
            }

            // Parse players
            val players = message.players.values.map { it.name }

            // Find current player
            val currentPlayer = message.players.values.find { it.name == _gameState.value.playerName }

            val isHost = currentPlayer?.isHost ?: false
            val myHand = currentPlayer?.hand?.map { cardDto ->
                val resourceId = cardResourceMap["${cardDto.suit}_${cardDto.rank}"] ?: R.drawable.card_back_red
                Card(cardDto.suit, cardDto.rank, resourceId, cardDto.id)
            } ?: emptyList()

            // Calculate other players' hand sizes
            val otherPlayersHandSizes = mutableMapOf<String, Int>()
            message.players.values.forEach { player ->
                if (player.name != _gameState.value.playerName) {
                    otherPlayersHandSizes[player.name] = player.hand.size
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

            val started = deckSize > 0 || table.any { it.isNotEmpty() } || discardPile.isNotEmpty() ||
                    message.players.values.any { it.hand.isNotEmpty() }

            _gameState.value = _gameState.value.copy(
                roomCode = roomCode,
                players = players,
                isHost = isHost,
                gameStarted = started,
                screen = "room",
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
                isLoadingGeneral = false,
                lastVersion = version
            )
            flushAllowed = true
            flushOutgoingQueue()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling game state update: ${e.message}")
            showError("Failed to update game state", ErrorType.TRANSIENT)
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
            
            val settings = WireRoomSettings(
                numDecks = _gameState.value.numDecks,
                includeJokers = _gameState.value.includeJokers,
                dealCount = 0
            )
            
            val message = WireMessage.CreateRoom(settings, _gameState.value.hostName.trim())
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
            
            val message = WireMessage.JoinRoom(trimmedCode, trimmedName)
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
                val message = WireMessage.StartGame(roomCode)
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
            
            val message = WireMessage.PlayCards(
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
            
            val message = WireMessage.DiscardCards(
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
            
            val message = WireMessage.DrawCard(
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
            
            val message = WireMessage.DrawFromDiscard(
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
            
            val message = WireMessage.ShuffleDeck(
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
            
            val message = WireMessage.DealCards(
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
            
            val message = WireMessage.MoveCards(
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
                val message = WireMessage.RecallLastDiscard(
                    roomCode = _gameState.value.roomCode,
                    playerName = _gameState.value.playerName
                )
                sendMessage(message)
            } else if (pileEligible) {
                val message = WireMessage.RecallLastPile(
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
            val message = WireMessage.ReorderHand(
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
        if (ClientConfig.IS_DEBUG) Log.d(TAG, "refreshPlayers called - players are updated via WebSocket")
    }

    fun restartGame() {
        viewModelScope.launch {
            _gameState.value = _gameState.value.copy(isLoadingGeneral = true)
            
            val message = WireMessage.RestartGame(
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
                delay(ClientConfig.ERROR_AUTO_DISMISS_MS)
                clearError()
            }
        }
    }

    private fun handleSuccess(message: String) {
        _gameState.value = _gameState.value.copy(
            successMessage = message,
            errorMessage = "",
            errorType = ErrorType.NONE
        )

        viewModelScope.launch {
            delay(ClientConfig.SUCCESS_AUTO_DISMISS_MS)
            if (_gameState.value.successMessage == message) {
                _gameState.value = _gameState.value.copy(successMessage = "")
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

    private fun sendMessage(message: WireMessage) {
        viewModelScope.launch {
            try {
                val connected = wsClient.isConnected && _gameState.value.isConnected
                if (!connected) {
                    if (outgoingQueue.size >= ClientConfig.OUTGOING_BUFFER_MAX) {
                        outgoingQueue.removeFirst()
                        if (ClientConfig.IS_DEBUG) Log.d(TAG, "Outgoing buffer full, dropping oldest message")
                    }
                    outgoingQueue.addLast(message)
                    if (ClientConfig.IS_DEBUG) Log.d(TAG, "Queued message: ${message::class.simpleName}")
                    return@launch
                }
                val jsonString = json.encodeToString(WireMessage.serializer(), message)
                if (ClientConfig.IS_DEBUG) Log.d(TAG, "Sending message: $jsonString")
                wsClient.send(Frame.Text(jsonString))
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message: ${e.message}")
                showError("Failed to send message: ${e.message}", ErrorType.TRANSIENT)
            }
        }
    }

    private fun flushOutgoingQueue() {
        viewModelScope.launch {
            while (outgoingQueue.isNotEmpty() && flushAllowed && _gameState.value.isConnected && wsClient.isConnected) {
                val msg = outgoingQueue.removeFirst()
                runCatching {
                    val jsonString = json.encodeToString(WireMessage.serializer(), msg)
                    if (ClientConfig.IS_DEBUG) Log.d(TAG, "Flushing queued message: $jsonString")
                    wsClient.send(Frame.Text(jsonString))
                }.onFailure {
                    if (ClientConfig.IS_DEBUG) Log.d(TAG, "Failed to flush queued message: ${it.message}")
                    outgoingQueue.addFirst(msg)
                    return@launch
                }
            }
        }
    }

    fun setWsClientForTest(client: WsClient) { this.wsClient = client }
    fun setConnectedForTest(connected: Boolean) { _gameState.value = _gameState.value.copy(isConnected = connected) }
    fun enqueueForTest(message: WireMessage) {
        if (outgoingQueue.size >= ClientConfig.OUTGOING_BUFFER_MAX) {
            outgoingQueue.removeFirst()
        }
        outgoingQueue.addLast(message)
    }
    suspend fun flushOutgoingQueueForTest() {
        while (outgoingQueue.isNotEmpty() && wsClient.isConnected) {
            val msg = outgoingQueue.removeFirst()
            val jsonString = json.encodeToString(WireMessage.serializer(), msg)
            wsClient.send(Frame.Text(jsonString))
        }
    }

    suspend fun pingOnceForTest() {
        if (_gameState.value.isConnected) {
            wsClient.send(Frame.Ping(byteArrayOf()))
        }
    }

    fun setFlushAllowedForTest(allowed: Boolean) { this.flushAllowed = allowed }
    fun triggerFlushForTest() { flushOutgoingQueue() }
    suspend fun flushOutgoingQueueRespectingGateForTest() {
        while (outgoingQueue.isNotEmpty() && flushAllowed && wsClient.isConnected) {
            val msg = outgoingQueue.removeFirst()
            val jsonString = json.encodeToString(WireMessage.serializer(), msg)
            wsClient.send(Frame.Text(jsonString))
        }
    }

    override fun onCleared() {
        super.onCleared()
        reconnectJob?.cancel()
        pingJob?.cancel()
        viewModelScope.launch {
            webSocketSession?.close()
            client.close()
        }
    }

    companion object {
        private const val TAG = "KtorViewModel"
    }
}