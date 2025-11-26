package com.cardsnow.backend.services

object ValidationService {

    /**
     * Validates a player name.
     * Requirements: 1-20 characters, alphanumeric only (letters, numbers, spaces, underscores, hyphens)
     */
    fun validatePlayerName(name: String): Boolean {
        if (name.isBlank() || name.length > 20) return false
        return name.matches(Regex("^[a-zA-Z0-9 _-]+$"))
    }

    /**
     * Validates a room code.
     * Requirements: exactly 4 digits
     */
    fun validateRoomCode(code: String): Boolean {
        return code.matches(Regex("^\\d{4}$"))
    }

    /**
     * Validates a list of card IDs.
     * Requirements: non-empty list, each ID is a non-blank string
     */
    fun validateCardIds(cardIds: List<String>): Boolean {
        if (cardIds.isEmpty()) return false
        return cardIds.all { it.isNotBlank() }
    }

    /**
     * Validates a card count for dealing.
     * Requirements: between 1 and 13 (standard deck has 13 ranks)
     */
    fun validateCardCount(count: Int): Boolean {
        return count in 1..13
    }

    /**
     * Validates room settings.
     * Requirements: numDecks between 1 and 8
     */
    fun validateRoomSettings(settings: com.cardsnow.backend.models.RoomSettings): Boolean {
        return settings.numDecks in 1..8
    }
}