package com.solverin.engine.math

import com.solverin.engine.core.HandClass
import com.solverin.engine.eval.CardRemoval

/**
 * Card-removal effects on villain response ranges.
 * foldProbability(h) is the per-hand fold equity used directly in raise EV:
 * blocking villain's continue range raises it, blocking his fold range lowers it.
 */
object Blockers {

    /** Fraction (0..1) of a range's combos removed by hero's cards. */
    fun removal(hero: Int, range: FloatArray): Float {
        val total = CardRemoval.total(range)
        return if (total <= 0.0) 0f else (1.0 - CardRemoval.remaining(hero, range) / total).toFloat()
    }

    /** P(villain folds | hero holds class [hero]) given his fold / continue split. */
    fun foldProbability(hero: Int, fold: FloatArray, cont: FloatArray): Double {
        val f = CardRemoval.remaining(hero, fold)
        val c = CardRemoval.remaining(hero, cont)
        return if (f + c <= 0.0) 0.0 else f / (f + c)
    }

    /**
     * Blocker quality in [-1, 1]: removal(continue) - removal(fold), normalized by max |score|.
     * > 0 = clean bluff (blocks continues, unblocks folds).
     */
    fun bluffScores(cont: FloatArray, fold: FloatArray): FloatArray {
        val out = FloatArray(HandClass.COUNT)
        var maxAbs = 1e-6f
        for (h in 0 until HandClass.COUNT) {
            val s = removal(h, cont) - removal(h, fold)
            out[h] = s
            maxAbs = maxOf(maxAbs, kotlin.math.abs(s))
        }
        for (h in out.indices) out[h] /= maxAbs
        return out
    }
}
