package com.solverin.engine.pipeline

import com.solverin.engine.flop.BoardStrength
import com.solverin.engine.flop.Combos
import com.solverin.engine.flop.FlopAnalysis
import com.solverin.engine.preflop.IsoRaiseInterceptor
import com.solverin.engine.preflop.SpotAnalysis
import com.solverin.engine.river.RiverAnalysis
import com.solverin.engine.river.RiverContext
import com.solverin.engine.river.RiverSession
import com.solverin.engine.turn.TurnAnalysis
import com.solverin.engine.turn.TurnContext
import com.solverin.engine.turn.TurnSession

/**
 * Mutable hand state carried street to street: board, every seat's Bayesian range (1326 weights), pot,
 * per-seat investment and stacks, initiative and accrued rake.
 * Accounting invariant: pot = dead blinds + Σ invested; stack(seat) = initial − invested(seat).
 */
class HandSessionState(val seats: List<Seat>, val initialStackBb: Double) {
    var street: Street = Street.PREFLOP
    var status: HandStatus = HandStatus.IN_PROGRESS
    var board: IntArray = IntArray(0)
    val ranges = HashMap<Seat, FloatArray>()
    val invested = HashMap<Seat, Double>().apply { for (s in seats) put(s, 0.0) }
    var deadBb: Double = 0.0
    var initiative: Seat = Seat.OPENER
    var rakeAccruedBb: Double = 0.0

    val potBb: Double get() = deadBb + invested.values.sum()
    fun stack(seat: Seat): Double = initialStackBb - invested.getValue(seat)
    val effectiveStackBb: Double get() = seats.minOf { stack(it) }.coerceAtLeast(0.0)

    fun invest(seat: Seat, amount: Double) {
        require(amount.isFinite() && amount >= -1e-9) { "Negative investment" }
        val a = amount.coerceAtLeast(0.0)
        check(a <= stack(seat) + 1e-6) { "Investment $a exceeds $seat stack ${stack(seat)}" }
        invested[seat] = invested.getValue(seat) + minOf(a, stack(seat))
    }

    fun mass(seat: Seat): Double = Combos.total(ranges.getValue(seat))
}

/**
 * Bayesian smoothing floor: a range that collapses below [MIN_MASS] after an off-tree action keeps the upper tail
 * ([TAIL] by made-hand strength on the board) of its prior at weight × [EPSILON] instead of becoming empty.
 */
object BayesianFloor {
    const val MIN_MASS = 1e-4
    const val EPSILON = 0.02f
    const val TAIL = 0.25
    /** Minimum weight of hero's own combo in his range (hero holds it even when off his solved strategy). */
    const val HERO_FLOOR = 1e-3f

    fun apply(filtered: FloatArray, prior: FloatArray, board: IntArray): Pair<FloatArray, Boolean> {
        if (Combos.total(filtered) >= MIN_MASS) return filtered to false
        val dead = Combos.mask(board)
        val out = filtered.copyOf()
        val strength = if (board.size in 3..5) BoardStrength(board) else null
        var added = 0.0
        for (k in 0 until Combos.COUNT) {
            if (!Combos.isLive(k, dead) || prior[k] <= 0f) continue
            if (strength != null && !strength.inTop(k, TAIL)) continue
            out[k] = maxOf(out[k], (prior[k] * EPSILON).coerceIn(0f, 1f)); added += out[k]
        }
        if (added <= 0.0) for (k in 0 until Combos.COUNT) if (Combos.isLive(k, dead) && prior[k] > 0f) out[k] = maxOf(out[k], prior[k] * EPSILON)
        if (Combos.total(out) <= 0.0) for (k in 0 until Combos.COUNT) if (Combos.isLive(k, dead)) out[k] = EPSILON
        return out to true
    }
}

/** Per-street audit record of the pipeline (hero's decision at that street plus range / pot bookkeeping). */
class StreetReport(
    val street: Street,
    val board: IntArray,
    val line: String,
    val potBb: Double,
    val effectiveStackBb: Double,
    val stacks: Map<Seat, Double>,
    val rangeMass: Map<Seat, Double>,
    val smoothed: Set<Seat>,
    val heroOffTree: Boolean,
    val heroMix: Map<String, Float>,
    val heroEv: Map<String, Double>,
    val heroBetFraction: Double?,
    val mdf: Double?,
    val rakeAccruedBb: Double,
    val latencyNanos: Long,
    val preflop: SpotAnalysis? = null,
    val iso: IsoRaiseInterceptor.Analysis? = null,
    val flop: FlopAnalysis? = null,
    val turn: TurnAnalysis? = null,
    val turnSession: TurnSession? = null,
    val turnContext: TurnContext? = null,
    val river: RiverAnalysis? = null,
    val riverSession: RiverSession? = null,
    val riverContext: RiverContext? = null,
) {
    val spr: Double get() = effectiveStackBb / potBb
    val heroBest: String get() = heroEv.maxByOrNull { it.value }?.key ?: heroMix.maxByOrNull { it.value }?.key ?: "-"
    val heroPlayed: String get() = heroMix.maxByOrNull { it.value }?.key ?: "-"
}

class HandResult(
    val script: HandScript,
    val reports: List<StreetReport>,
    val status: HandStatus,
    val finalPotBb: Double,
    val finalStacks: Map<Seat, Double>,
    val heroShowdownEquity: Double?,
    val rakeAccruedBb: Double,
    val totalNanos: Long,
) {
    fun report(street: Street): StreetReport? = reports.firstOrNull { it.street == street }
}
