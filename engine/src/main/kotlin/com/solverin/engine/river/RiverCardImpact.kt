package com.solverin.engine.river

import com.solverin.engine.eval.HandEvaluator

/** Terminal river card categories (priority = declaration order). */
enum class RiverCardCategory {
    FOUR_TO_A_FLUSH_OR_STRAIGHT, BOARD_PAIRING_RIVER, FRONT_DOOR_FLUSH_COMPLETING, BACKDOOR_FLUSH_COMPLETING,
    OVERCARD_RIVER, MISSED_DRAWS_BRICK,
}

/**
 * @property flags every matching category (bit = ordinal)
 * @property fourFlushSuit suit with ≥ 4 board cards, or −1
 * @property oneCardStraight a single hole card completes a straight on this board
 * @property drawHeavyTurn the turn board carried flush (2+ suited) or straight (3 ranks in a 5-window) draws
 */
data class RiverCardImpact(
    val riverCard: Int,
    val category: RiverCardCategory,
    val flags: Int,
    val fourFlushSuit: Int,
    val oneCardStraight: Boolean,
    val drawHeavyTurn: Boolean,
) {
    fun has(c: RiverCardCategory): Boolean = flags and (1 shl c.ordinal) != 0
    /** A card that completes an obvious draw or inverts the nuts (lead / polarized donk trigger). */
    val completing: Boolean get() = has(RiverCardCategory.FRONT_DOOR_FLUSH_COMPLETING) || has(RiverCardCategory.BOARD_PAIRING_RIVER) ||
        has(RiverCardCategory.FOUR_TO_A_FLUSH_OR_STRAIGHT) || has(RiverCardCategory.BACKDOOR_FLUSH_COMPLETING)
}

object RiverCardImpactEngine {

    private fun rankMask(cards: IntArray): Int { var m = 0; for (c in cards) m = m or (1 shl (c ushr 2)); return m }

    /** A single extra rank makes a straight with [mask] (and the board alone is not already a straight). */
    fun oneCardStraight(mask: Int): Boolean {
        if (HandEvaluator.straightHigh(mask) >= 0) return true
        for (r in 0 until 13) if (mask and (1 shl r) == 0 && HandEvaluator.straightHigh(mask or (1 shl r)) >= 0) return true
        return false
    }

    /** Highest rank a single hole card needs to make the best one-card straight, or −1. */
    fun topStraightCard(board: IntArray): Int {
        val mask = rankMask(board)
        var best = -1; var bestHigh = -1
        for (r in 0 until 13) {
            if (mask and (1 shl r) != 0) continue
            val h = HandEvaluator.straightHigh(mask or (1 shl r))
            if (h > bestHigh) { bestHigh = h; best = r }
        }
        return best
    }

    fun classify(turnBoard: IntArray, river: Int): RiverCardImpact {
        require(turnBoard.size == 4 && river !in turnBoard && river in 0..51) { "Invalid turn board / river" }
        val board = turnBoard + river
        val rRank = river ushr 2; val rSuit = river and 3
        val suitAll = IntArray(4); for (c in board) suitAll[c and 3]++
        val flopSuit = IntArray(4); for (i in 0 until 3) flopSuit[turnBoard[i] and 3]++
        val turnSuit = IntArray(4); for (c in turnBoard) turnSuit[c and 3]++
        val fourFlush = suitAll.indexOfFirst { it >= 4 }
        val mask = rankMask(board)
        var oneCard = false
        for (r in 0 until 13) if (mask and (1 shl r) == 0 && HandEvaluator.straightHigh(mask or (1 shl r)) >= 0) oneCard = true
        val turnMask = rankMask(turnBoard)
        var straightDraws = false
        for (lo in -1..8) {
            var n = 0
            for (r in lo until lo + 5) { val rr = if (r < 0) 12 else r; if (turnMask and (1 shl rr) != 0) n++ }
            if (n >= 3) straightDraws = true
        }
        val drawHeavy = turnSuit.any { it >= 2 } || straightDraws
        var flags = 0
        fun set(c: RiverCardCategory) { flags = flags or (1 shl c.ordinal) }
        if (fourFlush >= 0 || oneCard) set(RiverCardCategory.FOUR_TO_A_FLUSH_OR_STRAIGHT)
        if (turnBoard.any { (it ushr 2) == rRank }) set(RiverCardCategory.BOARD_PAIRING_RIVER)
        if (suitAll[rSuit] == 3 && flopSuit[rSuit] >= 2) set(RiverCardCategory.FRONT_DOOR_FLUSH_COMPLETING)
        if (suitAll[rSuit] == 3 && flopSuit[rSuit] == 1 && (turnBoard[3] and 3) == rSuit) set(RiverCardCategory.BACKDOOR_FLUSH_COMPLETING)
        if (rRank >= 11 && rRank > turnBoard.maxOf { it ushr 2 }) set(RiverCardCategory.OVERCARD_RIVER)
        if (flags == 0) set(RiverCardCategory.MISSED_DRAWS_BRICK)
        val primary = RiverCardCategory.entries.first { flags and (1 shl it.ordinal) != 0 }
        return RiverCardImpact(river, primary, flags, fourFlush, oneCard, drawHeavy)
    }
}
