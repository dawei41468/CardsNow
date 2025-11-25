package com.example.cardsnow

object CardGameLogic {
    private val rankOrder = mapOf(
        "Ace" to 1, "2" to 2, "3" to 3, "4" to 4, "5" to 5,
        "6" to 6, "7" to 7, "8" to 8, "9" to 9, "10" to 10,
        "Jack" to 11, "Queen" to 12, "King" to 13
    )

    private val suitOrder = mapOf(
        "Spades" to 1, "Hearts" to 2, "Clubs" to 3, "Diamonds" to 4
    )

    fun sortByRank(cards: List<Card>): List<Card> {
        return cards.sortedWith(compareBy<Card> { rankOrder[it.rank] ?: 99 }
            .thenBy { suitOrder[it.suit] ?: 99 })
    }

    fun sortBySuit(cards: List<Card>): List<Card> {
        return cards.sortedWith(compareBy<Card> { suitOrder[it.suit] ?: 99 }
            .thenBy { rankOrder[it.rank] ?: 99 })
    }
}