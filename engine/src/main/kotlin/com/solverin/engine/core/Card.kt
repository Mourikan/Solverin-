package com.solverin.engine.core

/**
 * Card encoded as Int 0..51: id = rank * 4 + suit.
 * rank 0 = '2' .. 12 = 'A'; suit 0..3.
 */
object Card {
    const val DECK_SIZE = 52
    const val RANK_CHARS = "23456789TJQKA"
    const val SUIT_CHARS = "cdhs"

    fun id(rank: Int, suit: Int): Int = (rank shl 2) or suit
    fun rank(card: Int): Int = card ushr 2
    fun suit(card: Int): Int = card and 3
    fun mask(card: Int): Long = 1L shl card

    fun rankOf(c: Char): Int {
        val r = RANK_CHARS.indexOf(c.uppercaseChar())
        require(r >= 0) { "Invalid rank '$c'" }
        return r
    }

    fun parse(s: String): Int {
        require(s.length == 2) { "Invalid card '$s'" }
        val suit = SUIT_CHARS.indexOf(s[1].lowercaseChar())
        require(suit >= 0) { "Invalid suit '${s[1]}'" }
        return id(rankOf(s[0]), suit)
    }

    fun toString(card: Int): String = "${RANK_CHARS[rank(card)]}${SUIT_CHARS[suit(card)]}"
}
