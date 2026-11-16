package com.solverin.engine.math

import com.solverin.engine.eval.CardRemoval

/**
 * P(Fold_villain) = 1 − MDF · BlockerFactor(hand) · TendencyMultiplier
 *
 *  MDF                = Pot_actual / (Pot_actual + BetSize)       (defense needed vs hero's added chips)
 *  TendencyMultiplier = villain's observed continue share / MDF   (> 1 over-defender, < 1 over-folder)
 *  BlockerFactor      = continue share left after hero's cards / unblocked continue share
 *                       (> 1: hero unblocks villain's continues, < 1: hero blocks them)
 */
object FoldModel {

    class Terms(
        val mdf: Double,
        val blockerFactor: Double,
        val tendency: Double,
        /** P(villain continues) = MDF · BF · TM, clamped to [0, 1]. */
        val pContinue: Double,
        /** Of the continues, share that re-raises (card-removal weighted). */
        val reraiseShare: Double,
    ) {
        val pFold: Double get() = 1.0 - pContinue
    }

    fun mdf(potActual: Double, betSize: Double): Double =
        if (potActual + betSize <= 0.0) 0.0 else potActual / (potActual + betSize)

    fun evaluate(hero: Int, potActual: Double, betSize: Double, fold: FloatArray, call: FloatArray, raise: FloatArray): Terms {
        val m = mdf(potActual, betSize)
        val tc = CardRemoval.total(call) + CardRemoval.total(raise)
        val tAll = tc + CardRemoval.total(fold)
        val rc = CardRemoval.remaining(hero, call)
        val rr = CardRemoval.remaining(hero, raise)
        val rAll = rc + rr + CardRemoval.remaining(hero, fold)
        val unblocked = if (tAll > 0) tc / tAll else 0.0
        val blocked = if (rAll > 0) (rc + rr) / rAll else 0.0
        val bf = if (unblocked > 0) blocked / unblocked else 1.0
        val tm = if (m > 0) unblocked / m else 0.0
        val pc = (m * bf * tm).coerceIn(0.0, 1.0)
        val share = if (rc + rr > 0) rr / (rc + rr) else 0.0
        return Terms(m, bf, tm, pc, share)
    }
}
