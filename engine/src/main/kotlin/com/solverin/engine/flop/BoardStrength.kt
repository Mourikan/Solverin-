package com.solverin.engine.flop

import com.solverin.engine.eval.HandEvaluator

/**
 * Made-hand strength of every live combo on the flop (5-card evaluation) and its percentile among all
 * live combos (1.0 = best). Nut / strong tiers drive ΔNuts, implied-odds payoff and vulnerability.
 */
class BoardStrength(board: IntArray) {
    val strength = IntArray(Combos.COUNT) { -1 }
    val percentile = FloatArray(Combos.COUNT)
    val category = IntArray(Combos.COUNT)

    init {
        val dead = Combos.mask(board)
        val ev = HandEvaluator()
        val cards = intArrayOf(board[0], board[1], board[2], 0, 0)
        val live = ArrayList<Int>(Combos.COUNT)
        for (k in 0 until Combos.COUNT) {
            if (!Combos.isLive(k, dead)) continue
            cards[3] = Combos.C1[k]; cards[4] = Combos.C2[k]
            strength[k] = ev.evaluate(cards, 5)
            category[k] = strength[k] ushr 20
            live += k
        }
        live.sortBy { strength[it] }
        var i = 0
        while (i < live.size) {
            var j = i
            while (j + 1 < live.size && strength[live[j + 1]] == strength[live[i]]) j++
            val pct = (j + 1).toFloat() / live.size
            for (m in i..j) percentile[live[m]] = pct
            i = j + 1
        }
    }

    /** True if combo is in the top [topFraction] of all live combos. */
    fun inTop(k: Int, topFraction: Double): Boolean = strength[k] >= 0 && percentile[k] > 1.0 - topFraction

    /** Weighted share of [range] in the top [topFraction] tier. */
    fun share(range: FloatArray, topFraction: Double): Double {
        var t = 0.0; var s = 0.0
        for (k in range.indices) {
            val w = range[k]
            if (w <= 0f || strength[k] < 0) continue
            t += w
            if (percentile[k] > 1.0 - topFraction) s += w
        }
        return if (t > 0) s / t else 0.0
    }
}

/** Flop draw outs of a combo (flush draws, open-enders, gutshots; made straights / flushes have 0). */
object Draws {
    fun outs(board: IntArray, k: Int): Int {
        val c1 = Combos.C1[k]; val c2 = Combos.C2[k]
        val all = intArrayOf(board[0], board[1], board[2], c1, c2)
        val suitCount = IntArray(4)
        for (c in all) suitCount[c and 3]++
        var flushOuts = 0
        for (s in 0 until 4) {
            val heroHas = (c1 and 3) == s || (c2 and 3) == s
            if (suitCount[s] >= 5 && heroHas) return 0
            if (suitCount[s] == 4 && heroHas) flushOuts = 9
        }
        var boardMask = 0
        for (c in board) boardMask = boardMask or (1 shl (c ushr 2))
        val heroMask = (1 shl (c1 ushr 2)) or (1 shl (c2 ushr 2))
        val full = boardMask or heroMask
        if (heroStraight(full, boardMask)) return 0
        var ranks = 0
        for (r in 0 until 13) {
            if (full and (1 shl r) != 0) continue
            if (heroStraight(full or (1 shl r), boardMask or (1 shl r))) ranks++
        }
        val straightOuts = when { ranks >= 2 -> 8; ranks == 1 -> 4; else -> 0 }
        val overlap = if (flushOuts > 0 && straightOuts > 0) 2 else 0
        return minOf(15, flushOuts + straightOuts - overlap)
    }

    private fun heroStraight(withHero: Int, boardOnly: Int): Boolean {
        val s = HandEvaluator.straightHigh(withHero)
        return s >= 0 && s > HandEvaluator.straightHigh(boardOnly)
    }
}
