package com.solverin.engine.preflop

import com.solverin.engine.core.HandClass
import com.solverin.engine.core.Position
import com.solverin.engine.model.VillainModel
import com.solverin.engine.range.SpotStrategy

/** Blind defense math for VS_RFI with hero in SB/BB. */
data class BlindDefenseReport(
    val openerWidth: Double,
    /** toCall / (pot after call). */
    val potOdds: Double,
    /** Raw equity needed to call. */
    val requiredEquity: Double,
    /** Equity needed after average OOP realization of the defending range. */
    val requiredEquityRealized: Double,
    /** Minimum defense frequency of both blinds vs the open size. */
    val mdf: Double,
    val defend: Double,
    val call: Double,
    val threeBet: Double,
    /** Share of 3bets with >= 50% equity vs villain's continue range. */
    val threeBetValueShare: Double,
    val style: Style,
) {
    enum class Style { LINEAR, POLARIZED, CALL_HEAVY, FOLD_HEAVY }

    companion object {
        fun of(
            hero: Position, opener: Position, ctx: PreflopContext, vm: VillainModel,
            strategy: SpotStrategy, valueEquity: FloatArray, avgRealization: Double,
        ): BlindDefenseReport {
            val o = ctx.sizing.open(opener)
            val blinds = Position.SB.blindBb + Position.BB.blindBb
            val potBefore = o + blinds - opener.blindBb
            val toCall = o - hero.blindBb
            val potOdds = toCall / (potBefore + toCall)
            val risk = o - opener.blindBb
            val reward = blinds - opener.blindBb
            val mdf = reward / (risk + reward)
            var v = 0.0; var t = 0.0
            for (h in 0 until HandClass.COUNT) {
                val w = strategy.raise(h) * HandClass.combos(h)
                t += w
                if (valueEquity[h] >= 0.5f) v += w
            }
            val share = if (t > 0) v / t else 0.0
            val call = strategy.callFrequency(); val tb = strategy.raiseFrequency()
            val style = when {
                call + tb < mdf * 0.6 -> Style.FOLD_HEAVY
                tb > 0 && share < 0.65 -> Style.POLARIZED
                call > 3 * tb -> Style.CALL_HEAVY
                else -> Style.LINEAR
            }
            return BlindDefenseReport(
                openerWidth = vm.profile.rfi(opener),
                potOdds = potOdds,
                requiredEquity = potOdds,
                requiredEquityRealized = if (avgRealization > 0) potOdds / avgRealization else potOdds,
                mdf = mdf,
                defend = call + tb, call = call, threeBet = tb,
                threeBetValueShare = share, style = style,
            )
        }
    }
}
