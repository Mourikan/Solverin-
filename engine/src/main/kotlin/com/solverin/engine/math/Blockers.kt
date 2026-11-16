package com.solverin.engine.math

import com.solverin.engine.core.HandClass

/**
 * Exact card-removal math on suit-symmetric class-weighted ranges.
 * Uses one representative combo per hero class (exact because villain ranges are suit-symmetric).
 */
object Blockers {

    /** Fraction (0..1) of villain's weighted combos removed when hero holds class [hero]. */
    fun removal(hero: Int, villain: FloatArray): Float {
        val heroMask = HandClass.representative(hero)
        var total = 0.0
        var blocked = 0.0
        val masks = HandClass.COMBO_MASKS
        val off = HandClass.OFFSETS
        for (v in 0 until HandClass.COUNT) {
            val w = villain[v]
            if (w <= 0f) continue
            val from = off[v]; val to = off[v + 1]
            var b = 0
            for (i in from until to) if (masks[i] and heroMask != 0L) b++
            total += w * (to - from)
            blocked += w * b
        }
        return if (total == 0.0) 0f else (blocked / total).toFloat()
    }

    /** Remaining weighted villain combos given hero class. */
    fun remainingCombos(hero: Int, villain: FloatArray): Double {
        val heroMask = HandClass.representative(hero)
        var rem = 0.0
        val masks = HandClass.COMBO_MASKS
        val off = HandClass.OFFSETS
        for (v in 0 until HandClass.COUNT) {
            val w = villain[v]
            if (w <= 0f) continue
            var free = 0
            for (i in off[v] until off[v + 1]) if (masks[i] and heroMask == 0L) free++
            rem += w * free
        }
        return rem
    }

    /**
     * Bluff blocker score per class: removal(continue) - removal(fold).
     * > 0: hero blocks villain's continuing range more than his folding range (good bluff candidate).
     * Normalized to [-1, 1] by max |score|.
     */
    fun bluffScores(villainContinue: FloatArray, villainFold: FloatArray): FloatArray {
        val out = FloatArray(HandClass.COUNT)
        var maxAbs = 1e-6f
        for (h in 0 until HandClass.COUNT) {
            val s = removal(h, villainContinue) - removal(h, villainFold)
            out[h] = s
            if (kotlin.math.abs(s) > maxAbs) maxAbs = kotlin.math.abs(s)
        }
        for (h in out.indices) out[h] /= maxAbs
        return out
    }

    /** Villain's continuing vs folding fraction after hero's blockers. */
    fun foldProbability(hero: Int, villainContinue: FloatArray, villainFold: FloatArray): Double {
        val c = remainingCombos(hero, villainContinue)
        val f = remainingCombos(hero, villainFold)
        return if (c + f == 0.0) 0.0 else f / (c + f)
    }
}
