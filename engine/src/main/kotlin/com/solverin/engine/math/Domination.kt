package com.solverin.engine.math

import com.solverin.engine.core.HandClass
import com.solverin.engine.eval.CardRemoval

/**
 * DominationPenalty input: share of villain's range (card-removal weighted) that dominates hero
 * (shares one rank, holds a higher kicker). Pairs are never kicker-dominated.
 */
object Domination {
    private val DOMINATORS: Array<IntArray> = Array(HandClass.COUNT) { h ->
        if (HandClass.isPair(h)) IntArray(0)
        else (0 until HandClass.COUNT).filter { v -> dominates(v, h) }.toIntArray()
    }

    fun dominates(v: Int, h: Int): Boolean {
        if (HandClass.isPair(v) || HandClass.isPair(h)) return false
        val vh = HandClass.highRank(v); val vl = HandClass.lowRank(v)
        val hh = HandClass.highRank(h); val hl = HandClass.lowRank(h)
        return (vh == hh && vl > hl) || (vh == hl && vl > hh) || (vl == hh && vh > hl) || (vl == hl && vh > hh)
    }

    fun share(h: Int, villain: FloatArray): Double {
        val dom = DOMINATORS[h]
        if (dom.isEmpty()) return 0.0
        val all = CardRemoval.remaining(h, villain)
        if (all <= 0.0) return 0.0
        var d = 0.0
        for (v in dom) d += villain[v] * CardRemoval.freeCombos(h, v)
        return d / all
    }
}
