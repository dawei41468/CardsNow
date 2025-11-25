package com.cardsnow.backend.services

import com.cardsnow.backend.models.*
import java.util.*

class GameService {
    
    fun generateDeck(settings: RoomSettings): List<Card> {
        val suits = listOf("Spades", "Hearts", "Clubs", "Diamonds")
        val ranks = listOf("Ace", "2", "3", "4", "5", "6", "7", "8", "9", "10", "Jack", "Queen", "King")
        
        val standardDeck = suits.flatMap { suit ->
            ranks.map { rank ->
                Card(suit, rank, 0, UUID.randomUUID().toString())
            }
        }
        
        val jokers = if (settings.includeJokers) {
            listOf(
                Card("Joker", "Red", 0, UUID.randomUUID().toString()),
                Card("Joker", "Black", 0, UUID.randomUUID().toString())
            )
        } else {
            emptyList()
        }
        
        return buildList {
            repeat(settings.numDecks) {
                addAll(standardDeck.map { 
                    Card(it.suit, it.rank, it.resourceId, UUID.randomUUID().toString()) 
                })
                addAll(jokers.map {
                    Card(it.suit, it.rank, it.resourceId, UUID.randomUUID().toString())
                })
            }
        }.shuffled()
    }
    
    fun startGame(room: Room, players: List<String>): Result<GameState> {
        val deck = generateDeck(room.settings)

        val newGameState = GameState(
            deck = deck,
            table = emptyList(),
            discardPile = emptyList()
        )

        // Set all player hands to empty
        players.forEach { playerName ->
            room.players[playerName]?.let { player ->
                room.players[playerName] = player.copy(hand = emptyList())
            }
        }

        room.state = RoomState.STARTED
        room.updateLastActive()

        return Result.success(newGameState)
    }
    
    fun playCards(room: Room, playerName: String, cardIds: List<String>): Result<GameState> {
        val player = room.players[playerName] ?: return Result.failure(Exception("Player not found"))
        val cardsToPlay = player.hand.filter { cardIds.contains(it.id) }

        if (cardsToPlay.isEmpty()) {
            return Result.failure(Exception("No valid cards selected"))
        }

        val newHand = player.hand.filterNot { cardIds.contains(it.id) }
        val currentTable = room.gameState.table.toMutableList()
        currentTable.add(cardsToPlay)

        // Update room state
        room.players[playerName] = player.copy(hand = newHand)
        val updatedGameState = room.gameState.copy(
            table = currentTable,
            lastPlayed = LastPlayed(playerName, cardsToPlay.map { it.id })
        )

        room.updateLastActive()
        return Result.success(updatedGameState)
    }
    
    fun discardCards(room: Room, playerName: String, cardIds: List<String>): Result<GameState> {
        val player = room.players[playerName] ?: return Result.failure(Exception("Player not found"))
        val cardsToDiscard = player.hand.filter { cardIds.contains(it.id) }

        if (cardsToDiscard.isEmpty()) {
            return Result.failure(Exception("No valid cards selected"))
        }

        val newHand = player.hand.filterNot { cardIds.contains(it.id) }
        val newDiscardPile = room.gameState.discardPile + cardsToDiscard

        room.players[playerName] = player.copy(hand = newHand)
        val updatedGameState = room.gameState.copy(discardPile = newDiscardPile)
        room.updateLastActive()

        return Result.success(updatedGameState)
    }
    
    fun drawCard(room: Room, playerName: String): Result<Pair<Card, GameState>> {
        val player = room.players[playerName] ?: return Result.failure(Exception("Player not found"))
        val deck = room.gameState.deck

        if (deck.isEmpty()) {
            return Result.failure(Exception("Deck is empty"))
        }

        val drawnCard = deck.first()
        val newDeck = deck.drop(1)
        val newHand = player.hand + drawnCard

        room.players[playerName] = player.copy(hand = newHand)
        val updatedGameState = room.gameState.copy(deck = newDeck)
        room.updateLastActive()

        return Result.success(Pair(drawnCard, updatedGameState))
    }
    
    fun drawFromDiscard(room: Room, playerName: String): Result<GameState> {
        val player = room.players[playerName] ?: return Result.failure(Exception("Player not found"))
        val discardPile = room.gameState.discardPile

        if (discardPile.isEmpty()) {
            return Result.failure(Exception("Discard pile is empty"))
        }

        val drawnCard = discardPile.last()
        val newDiscardPile = discardPile.dropLast(1)
        val newHand = player.hand + drawnCard

        room.players[playerName] = player.copy(hand = newHand)
        val updatedGameState = room.gameState.copy(discardPile = newDiscardPile)
        room.updateLastActive()

        return Result.success(updatedGameState)
    }
    
    fun shuffleDeck(room: Room, playerName: String): Result<GameState> {
        val table = room.gameState.table
        if (table.isEmpty()) {
            return Result.failure(Exception("No cards on table to shuffle"))
        }

        val allTableCards = table.flatten().shuffled()
        val currentDeck = room.gameState.deck
        val newDeck = (currentDeck + allTableCards).shuffled()

        val updatedGameState = room.gameState.copy(
            deck = newDeck,
            table = emptyList()
        )
        room.updateLastActive()

        return Result.success(updatedGameState)
    }
    
    fun dealCards(room: Room, playerName: String, count: Int): Result<Pair<Map<String, List<Card>>, GameState>> {
        if (!room.players[playerName]?.isHost!!) {
            return Result.failure(Exception("Only host can deal cards"))
        }

        val deck = room.gameState.deck
        val players = room.players.keys.toList()

        if (deck.size < count * players.size) {
            return Result.failure(Exception("Not enough cards to deal"))
        }

        val newDeck = deck.drop(count * players.size)
        val dealtCards = deck.take(count * players.size)
        val playerHands = mutableMapOf<String, List<Card>>()

        players.forEach { player ->
            val currentHand = room.players[player]?.hand ?: emptyList()
            val startIndex = players.indexOf(player) * count
            val playerCards = dealtCards.subList(startIndex, startIndex + count)
            val newHand = currentHand + playerCards

            room.players[player]?.let { p ->
                room.players[player] = p.copy(hand = newHand)
            }
            playerHands[player] = newHand
        }

        val updatedGameState = room.gameState.copy(deck = newDeck)
        room.updateLastActive()

        return Result.success(Pair(playerHands, updatedGameState))
    }
    
    fun moveCards(room: Room, fromPlayer: String, toPlayer: String, cardIds: List<String>): Result<GameState> {
        val fromPlayerObj = room.players[fromPlayer] ?: return Result.failure(Exception("From player not found"))
        val toPlayerObj = room.players[toPlayer] ?: return Result.failure(Exception("To player not found"))

        val cardsToMove = fromPlayerObj.hand.filter { cardIds.contains(it.id) }
        if (cardsToMove.isEmpty()) {
            return Result.failure(Exception("No valid cards selected"))
        }

        val newFromHand = fromPlayerObj.hand.filterNot { cardIds.contains(it.id) }
        val newToHand = toPlayerObj.hand + cardsToMove

        room.players[fromPlayer] = fromPlayerObj.copy(hand = newFromHand)
        room.players[toPlayer] = toPlayerObj.copy(hand = newToHand)
        room.updateLastActive()

        return Result.success(room.gameState) // gameState unchanged
    }
    
    fun recallLastPile(room: Room, playerName: String): Result<GameState> {
        val player = room.players[playerName] ?: return Result.failure(Exception("Player not found"))
        val lastPlayed = room.gameState.lastPlayed

        if (lastPlayed.player != playerName) {
            return Result.failure(Exception("Not your last play"))
        }

        val lastHandIds = lastPlayed.cardIds
        val currentTable = room.gameState.table

        if (currentTable.isEmpty()) {
            return Result.failure(Exception("No valid pile to recall"))
        }

        val lastPile = currentTable.last()
        val lastPileIds = lastPile.map { it.id }

        if (lastPileIds != lastHandIds) {
            return Result.failure(Exception("No valid pile to recall"))
        }

        val newTable = currentTable.dropLast(1)
        val lastHandCards = lastPile
        val newHand = player.hand + lastHandCards

        room.players[playerName] = player.copy(hand = newHand)
        val updatedGameState = room.gameState.copy(
            table = newTable,
            lastPlayed = LastPlayed()
        )
        room.updateLastActive()

        return Result.success(updatedGameState)
    }
    
    fun sortHand(hand: List<Card>, sortBy: SortType): List<Card> {
        return when (sortBy) {
            SortType.RANK -> hand.sortedWith(compareBy({ getRankValue(it.rank) }, { getSuitOrder(it.suit) }))
            SortType.SUIT -> hand.sortedWith(compareBy({ getSuitOrder(it.suit) }, { getRankValue(it.rank) }))
        }
    }
    
    private fun getRankValue(rank: String): Int = when (rank) {
        "Ace" -> 1; "2" -> 2; "3" -> 3; "4" -> 4; "5" -> 5; "6" -> 6; "7" -> 7; "8" -> 8; "9" -> 9; "10" -> 10
        "Jack" -> 11; "Queen" -> 12; "King" -> 13; else -> 0
    }
    
    private fun getSuitOrder(suit: String): Int = when (suit) {
        "Spades" -> 1; "Hearts" -> 2; "Clubs" -> 3; "Diamonds" -> 4; else -> 0
    }
}