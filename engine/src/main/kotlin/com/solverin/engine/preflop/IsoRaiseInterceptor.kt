package com.solverin.engine.preflop

import com.solverin.engine.core.HandClass
import com.solverin.engine.core.Position
import com.solverin.engine.core.RakeStructure
import com.solverin.engine.core.Stake
import com.solverin.engine.eval.EquityTable
import com.solverin.engine.eval.RangeEquity
import com.solverin.engine.math.RakeAttribution
import com.solverin.engine.math.RakeModel
import com.solverin.engine.model.HandRanking
import com.solverin.engine.range.RangeParser

/** Hero's options when one or more players limped in front. */
enum class IsoDecision { ISO_RAISE, OVER_LIMP, CHECK_BB, FOLD }

/**
 * Recreational limper tendencies: limp range (class weights), fold vs an isolation raise, limp-reraise frequency.
 * Passive limpers call isolation raises with the top (1 − foldToIso − limpReraise) of the limp range plus the
 * limp-reraises on top.
 */
data class LimperProfile(val limpRange: FloatArray, val foldToIso: Double, val limpReraise: Double) {
    init {
        require(limpRange.size == HandClass.COUNT && limpRange.all { it.isFinite() && it in 0f..1f })
        require(foldToIso in 0.0..1.0 && limpReraise in 0.0..1.0 && foldToIso + limpReraise <= 1.0)
    }

    companion object {
        const val RECREATIONAL_LIMP = "22+,A2s+,A2o+,K2s+,K7o+,Q4s+,Q8o+,J6s+,J8o+,T6s+,T8o+,96s+,98o,85s+,87o,74s+,76o,64s+,53s+,43s"

        fun population(stake: Stake): LimperProfile {
            val r = RangeParser.parse(RECREATIONAL_LIMP)
            return when (stake) {
                Stake.NL2 -> LimperProfile(r, 0.30, 0.03)
                Stake.NL5 -> LimperProfile(r, 0.35, 0.03)
                Stake.NL10 -> LimperProfile(r, 0.40, 0.04)
                Stake.NL25 -> LimperProfile(r, 0.48, 0.05)
                Stake.NL50, Stake.NL100, Stake.NL200 -> LimperProfile(r, 0.55, 0.06)
            }
        }
    }
}

/**
 * External isolation interceptor (microstakes limpers) that does not touch [com.solverin.engine.range.SpotKey] or the
 * 165 binary preflop nodes. Sizing_ISO = 3.5bb + 1bb · N + 0.5bb OOP. Master preflop EV per hand class:
 *
 *  EV(ISO)  = f^N·Pot₀ + Σ_k C(N,k)·c^k·f^(N−k)·[EQ_k·R_iso·Pot_k − Inv − Rake(Pot_k)] − P(limp-raise)·Inv
 *  EV(LIMP) = EQ_field·R_passive·Pot_limp − (1 − posted) − Rake;   EV(CHECK_BB) = EQ_field·R_passive·Pot − Rake;   EV(FOLD) = 0
 *
 * with c = 1 − f − r per limper (independent), EQ_k the k-way field equity vs the limpers' calling range.
 */
class IsoRaiseInterceptor(table: EquityTable = EquityTable.get()) {
    private val equity = RangeEquity(table)
    private val ranking = HandRanking(table)
    private val random = FloatArray(HandClass.COUNT) { 1f }

    class Analysis(
        val hero: Position,
        val limpers: List<Position>,
        val sizeBb: Double,
        val decision: Array<IsoDecision>,
        val ev: Array<Map<IsoDecision, Double>>,
        /** Hero's isolation range (class weights: 1 where ISO_RAISE is the max-EV option). */
        val isoRange: FloatArray,
        /** One limper's continuing range vs the isolation raise (calls + limp-reraises), class weights. */
        val callRange: FloatArray,
        val pFoldAll: Double,
    ) {
        fun decision(hand: Int): IsoDecision = decision[hand]
        fun ev(hand: Int): Map<IsoDecision, Double> = ev[hand]
        /** Combo-weighted share of hands that isolate. */
        val isoFrequency: Double get() {
            var n = 0.0; var d = 0.0
            for (h in 0 until HandClass.COUNT) { val c = HandClass.combos(h).toDouble(); d += c; n += c * isoRange[h] }
            return n / d
        }
    }

    fun sizing(limpers: Int, outOfPosition: Boolean): Double = 3.5 + 1.0 * limpers + if (outOfPosition) 0.5 else 0.0

    /** Continuing range vs the iso raise: the strongest (1 − foldToIso) of the limp range by value ordering. */
    fun callRange(profile: LimperProfile): FloatArray {
        val total = (0 until HandClass.COUNT).sumOf { profile.limpRange[it].toDouble() * HandClass.combos(it) }
        var left = total * (1 - profile.foldToIso)
        val out = FloatArray(HandClass.COUNT)
        for (h in ranking.byStrength) {
            if (left <= 0.0) break
            val w = profile.limpRange[h]
            if (w <= 0f) continue
            val c = w * HandClass.combos(h)
            val take = minOf(c.toDouble(), left)
            out[h] = (take / HandClass.combos(h)).toFloat()
            left -= take
        }
        return out
    }

    private fun choose(n: Int, k: Int): Double { var r = 1.0; for (i in 1..k) r = r * (n - k + i) / i; return r }

    private fun field(e: Float, players: Int): Float { var x = e; repeat(players - 1) { x = equity.multiway(x, e) }; return x }

    fun analyze(
        hero: Position, limpers: List<Position>, stake: Stake, stackBb: Double = 100.0,
        profile: LimperProfile = LimperProfile.population(stake), rake: RakeStructure = stake.defaultRake,
        attribution: RakeAttribution = RakeAttribution.WINNER_SHARE,
    ): Analysis {
        require(limpers.isNotEmpty() && limpers.none { it.isBlind } && limpers.all { it.ordinal < hero.ordinal }) {
            "Limpers must be non-blind players acting before hero"
        }
        val n = limpers.size
        val oop = limpers.any { hero.postflopOrder < it.postflopOrder }
        val size = minOf(sizing(n, oop), stackBb)
        val posted = hero.blindBb
        val blinds = Position.SB.blindBb + Position.BB.blindBb
        val pot0 = n * 1.0 + blinds
        val call = callRange(profile)
        val f = profile.foldToIso; val r = profile.limpReraise; val c = 1 - f - r
        val pFoldAll = Math.pow(f, n.toDouble())
        val pReraise = 1 - Math.pow(1 - r, n.toDouble())
        val rIso = (if (oop) 0.85 else 1.0) * 1.05
        val rPassive = (if (oop) 0.85 else 1.0) * 0.95
        val decision = Array(HandClass.COUNT) { IsoDecision.FOLD }
        val evs = Array(HandClass.COUNT) { emptyMap<IsoDecision, Double>() }
        val iso = FloatArray(HandClass.COUNT)
        for (h in 0 until HandClass.COUNT) {
            val inv = size - posted
            val e1 = equity.vs(h, call)
            var isoEv = pFoldAll * pot0 - pReraise * inv
            for (k in 1..n) {
                val pk = choose(n, k) * Math.pow(c, k.toDouble()) * Math.pow(f, (n - k).toDouble())
                val potK = pot0 + k * (size - 1.0) + inv
                val eq = minOf(field(e1, k) * rIso, 0.98)
                pk.let { isoEv += it * (eq * potK - inv - RakeModel.charge(rake, attribution, potK, stackBb - size, eq)) }
            }
            val eLimp = equity.vs(h, profile.limpRange)
            val eBb = equity.vs(h, random)
            val fieldPassive = equity.multiway(field(eLimp, n), eBb).coerceAtMost(field(eLimp, n))
            val m = LinkedHashMap<IsoDecision, Double>()
            m[IsoDecision.ISO_RAISE] = isoEv
            if (hero == Position.BB) {
                val eqr = minOf(field(eLimp, n) * rPassive, 0.98)
                m[IsoDecision.CHECK_BB] = eqr * pot0 - RakeModel.charge(rake, attribution, pot0, stackBb - 1.0, eqr)
            } else {
                val potL = pot0 + (1.0 - posted)
                val eqr = minOf(fieldPassive * rPassive, 0.98)
                m[IsoDecision.OVER_LIMP] = eqr * potL - (1.0 - posted) - RakeModel.charge(rake, attribution, potL, stackBb - 1.0, eqr)
                m[IsoDecision.FOLD] = 0.0
            }
            val best = m.maxBy { it.value }.key
            decision[h] = best; evs[h] = m
            if (best == IsoDecision.ISO_RAISE) iso[h] = 1f
        }
        return Analysis(hero, limpers, size, decision, evs, iso, call, pFoldAll)
    }
}
