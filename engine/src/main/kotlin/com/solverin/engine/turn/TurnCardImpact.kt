package com.solverin.engine.turn

import com.solverin.engine.eval.HandEvaluator
import com.solverin.engine.flop.FlopIsomorphismEngine

/** Strategic relation between the canonical flop and the turn card (priority order = enum order). */
enum class TurnCardCategory { BOARD_PAIRING, FLUSH_COMPLETING, STRAIGHT_COMPLETING, OVERCARD_SCARE, BRICK_BLANK }

/**
 * @property category primary category (first matching in priority order)
 * @property flags bit set of every matching category (bit = ordinal)
 * @property suitCountAfter cards of the turn's suit on the 4-card board
 * @property newStraightPairs hole-rank pairs that make a straight on the turn but not on the flop
 */
data class TurnCardImpact(
    val flopId: Int,
    val turnCard: Int,
    val category: TurnCardCategory,
    val flags: Int,
    val suitCountAfter: Int,
    val newStraightPairs: Int,
) {
    fun has(c: TurnCardCategory): Boolean = flags and (1 shl c.ordinal) != 0
}

/** Turn card classification in O(1) table reads + 78 rank-pair straight checks. */
object TurnCardImpactEngine {

    fun classify(flop: IntArray, turn: Int): TurnCardImpact {
        require(flop.size == 3 && turn !in flop && turn in 0..51) { "Invalid flop / turn" }
        val id = FlopIsomorphismEngine.canonicalId(flop)
        val tRank = turn ushr 2
        val tSuit = turn and 3
        val ranks = flop.map { it ushr 2 }
        val suitCount = 1 + flop.count { (it and 3) == tSuit }
        var flopMask = 0
        for (r in ranks) flopMask = flopMask or (1 shl r)
        val turnMask = flopMask or (1 shl tRank)
        var before = 0; var after = 0
        for (x in 0 until 13) for (y in x + 1 until 13) {
            val pair = (1 shl x) or (1 shl y)
            val sFlop = HandEvaluator.straightHigh(flopMask or pair) >= 0
            val sTurn = HandEvaluator.straightHigh(turnMask or pair) >= 0
            if (sFlop) before++
            if (sTurn && !sFlop) after++
        }
        var flags = 0
        fun set(c: TurnCardCategory) { flags = flags or (1 shl c.ordinal) }
        if (tRank in ranks) set(TurnCardCategory.BOARD_PAIRING)
        if (suitCount >= 3) set(TurnCardCategory.FLUSH_COMPLETING)
        if (after > 0 && tRank !in ranks) set(TurnCardCategory.STRAIGHT_COMPLETING)
        if (tRank > ranks.max()) set(TurnCardCategory.OVERCARD_SCARE)
        if (flags == 0) set(TurnCardCategory.BRICK_BLANK)
        val primary = TurnCardCategory.entries.first { flags and (1 shl it.ordinal) != 0 }
        return TurnCardImpact(id, turn, primary, flags, suitCount, after)
    }
}
