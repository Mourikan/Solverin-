package com.solverin.engine.turn

import com.solverin.engine.flop.ComboPartition
import com.solverin.engine.flop.Combos
import com.solverin.engine.flop.FlopAction
import com.solverin.engine.flop.FlopContext
import com.solverin.engine.flop.FlopEquity
import com.solverin.engine.flop.FlopMathEngine
import com.solverin.engine.flop.FlopNodeType
import com.solverin.engine.flop.FlopSession
import com.solverin.engine.flop.FlopSpot
import com.solverin.engine.flop.FlopVillainNode

/**
 * Bayesian flop → turn bridge: w_turn(c) = w_flop(c) · P(flop action | c), then every combo holding the turn
 * card is removed. Hero's P(a | c) is his solved flop strategy ([com.solverin.engine.flop.FlopAnalysis]
 * frequencies); villain's comes from the flop response model partitions (card-removal exact).
 *
 * Lines: BET_CALL (one bets, the other calls), CHECK_CHECK, RAISE_CALL (bet, raise, call),
 * MULTIWAY_3WAY (bet called by both opponents; the turn is then valued with realization × 0.88 per extra
 * opponent and squeeze risk in sandwiched calls).
 */
object FlopToTurnRangeFilter {

    /** Filtered turn ranges plus the pot / stack after the flop line. */
    class TurnRanges(
        val hero: FloatArray,
        val villain: FloatArray,
        val extra: FloatArray?,
        val potBb: Double,
        val effectiveStackBb: Double,
        val heroIsAggressor: Boolean,
        val heroInPosition: Boolean,
        val line: FlopLine,
    ) {
        fun spot(flop: IntArray, turnCard: Int, facingBetBb: Double = 0.0, heroInPosition: Boolean = this.heroInPosition): TurnSpot =
            TurnSpot(flop + turnCard, hero, villain, heroInPosition, heroIsAggressor, line, potBb, effectiveStackBb, facingBetBb, extra)
    }

    /** w(c) · P(c), with combos holding [turnCard] (or flop cards) removed. Weights validated in [0, 1]. */
    fun filter(flopRange: FloatArray, actionProbability: FloatArray, turnCard: Int, dead: Long = 0L): FloatArray {
        require(flopRange.size == Combos.COUNT && actionProbability.size == Combos.COUNT) { "Ranges must have ${Combos.COUNT} combos" }
        require(turnCard in 0..51) { "Invalid turn card" }
        require(flopRange.all { it.isFinite() && it in 0f..1f }) { "Flop weights must be finite and in [0, 1]" }
        require(actionProbability.all { it.isFinite() && it in -1e-4f..1.0001f }) { "Action probabilities must be finite and in [0, 1]" }
        val gone = dead or (1L shl turnCard)
        return FloatArray(Combos.COUNT) { k ->
            if (!Combos.isLive(k, gone)) 0f else (flopRange[k] * actionProbability[k].coerceIn(0f, 1f)).coerceIn(0f, 1f)
        }
    }

    /** P(partition branch | combo) = branch[c] / prior[c]. */
    private fun conditional(branch: FloatArray, prior: FloatArray): FloatArray =
        FloatArray(Combos.COUNT) { k -> if (prior[k] <= 0f) 0f else (branch[k] / prior[k]).coerceIn(0f, 1f) }

    /**
     * Solves the flop spot with [flopCtx] and filters both ranges through [line] for [turnCard].
     * Hero's position is kept; who holds the initiative on the turn follows the line.
     */
    fun fromFlop(
        flopSpot: FlopSpot, flopCtx: FlopContext, line: FlopLine, turnCard: Int, stride: Int = FlopEquity.FAST_STRIDE,
        /** Bet actually made (bb) when it differs from the solved size; hero's P(bet | c) stays the solved strategy. */
        betSizeBb: Double? = null,
        /** Already-built session of [flopSpot] (its equity matrix is reused). */
        session: FlopSession? = null,
    ): TurnRanges {
        require(turnCard !in flopSpot.board) { "Turn card is on the flop" }
        require((line == FlopLine.MULTIWAY_3WAY) == (flopSpot.extraRange != null)) { "MULTIWAY_3WAY requires a 3-way flop spot (and only it)" }
        val ses = session?.also { require(it.spot === flopSpot) { "Session of another spot" } } ?: FlopSession(flopSpot, stride)
        val a = ses.analyze(flopCtx)
        val m = FlopMathEngine(flopSpot, flopCtx, ses.matrix, null, ses.extraMatrix)
        val dead = Combos.mask(flopSpot.board)
        val pot = flopSpot.potBb
        val stack = flopSpot.effectiveStackBb
        val heroFreq = { act: FlopAction -> FloatArray(Combos.COUNT) { a.frequency(act, it) } }
        val heroBets = flopSpot.nodeType != FlopNodeType.VS_BET
        fun ranges(hp: FloatArray, vp: FloatArray, xp: FloatArray?, potAfter: Double, invested: Double, heroAgg: Boolean) = TurnRanges(
            filter(m.hero, hp, turnCard, dead), filter(m.villain, vp, turnCard, dead),
            xp?.let { filter(m.extra!!, it, turnCard, dead) }, potAfter, (stack - invested).coerceAtLeast(0.0),
            heroAgg, flopSpot.heroInPosition, line,
        )
        when (line) {
            FlopLine.CHECK_CHECK -> {
                require(heroBets) { "CHECK_CHECK needs a flop node where hero can check" }
                return ranges(heroFreq(FlopAction.CHECK), conditional(m.villainFirstAction.fold, m.villain), null, pot, 0.0, flopSpot.heroIsAggressor)
            }
            FlopLine.BET_CALL, FlopLine.MULTIWAY_3WAY -> {
                if (heroBets) {
                    val b = betSizeBb?.let { minOf(it, stack) } ?: requireNotNull(a.betSizeBb) { "No flop bet available (stack 0)" }
                    val action = a.actions.first { it == FlopAction.BET || it == FlopAction.DONK }
                    val donk = flopSpot.nodeType == FlopNodeType.FIRST_TO_ACT && !flopSpot.heroIsAggressor
                    val facing = if (flopSpot.nodeType == FlopNodeType.FIRST_TO_ACT) m.villain else m.villainFirstAction.fold
                    val resp = m.villainModel.respondToBet(facing, pot, b, if (donk) FlopVillainNode.VS_DONK else FlopVillainNode.VS_BET)
                    val xp = if (line == FlopLine.MULTIWAY_3WAY) conditional(m.extraModel!!.respondToBet(m.extraNodeRange!!, pot, b).call, m.extra!!) else null
                    val callers = if (xp != null) 2 else 1
                    return ranges(heroFreq(action), conditional(resp.call, m.villain), xp, pot + b * (1 + callers), b, heroAgg = true)
                }
                val bet = minOf(flopSpot.facingBetBb, stack)
                val betRange = betRange(m, flopSpot)
                val xp = if (line == FlopLine.MULTIWAY_3WAY) conditional(m.extraModel!!.respondToBet(m.extraNodeRange!!, pot + bet, bet).call, m.extra!!) else null
                val callers = if (xp != null) 2 else 1
                return ranges(heroFreq(FlopAction.CALL), conditional(betRange, m.villain), xp, pot + bet * (1 + callers), bet, heroAgg = false)
            }
            FlopLine.RAISE_CALL -> {
                if (!heroBets) {
                    // Villain bet, hero raised, villain called.
                    val bet = minOf(flopSpot.facingBetBb, stack)
                    val betRange = betRange(m, flopSpot)
                    val to = m.raiseTo(bet, pot + bet)
                    val called = m.villainModel.respondToRaise(betRange).call
                    return ranges(heroFreq(FlopAction.RAISE), conditional(called, m.villain), null, pot + 2 * to, to, heroAgg = true)
                }
                // Hero bet, villain raised, hero called the raise.
                val b = betSizeBb?.let { minOf(it, stack) } ?: requireNotNull(a.betSizeBb) { "No flop bet available (stack 0)" }
                val action = a.actions.first { it == FlopAction.BET || it == FlopAction.DONK }
                val donk = flopSpot.nodeType == FlopNodeType.FIRST_TO_ACT && !flopSpot.heroIsAggressor
                val facing = if (flopSpot.nodeType == FlopNodeType.FIRST_TO_ACT) m.villain else m.villainFirstAction.fold
                val resp: ComboPartition = m.villainModel.respondToBet(facing, pot, b, if (donk) FlopVillainNode.VS_DONK else FlopVillainNode.VS_BET)
                val y = m.raiseTo(b, pot + b)
                val bet = heroFreq(action)
                val hp = FloatArray(Combos.COUNT) { h ->
                    if (bet[h] <= 0f || m.hero[h] <= 0f) 0f
                    else if (m.reraiseResponse(h, pot, b, 0.0, 0.0, resp, 1).best == FlopAction.CALL) bet[h] else 0f
                }
                return ranges(hp, conditional(resp.raise, m.villain), null, pot + 2 * y, y, heroAgg = false)
            }
        }
    }

    private fun betRange(m: FlopMathEngine, spot: FlopSpot): FloatArray =
        if (spot.isDonk) m.villainModel.firstAction(m.villain, FlopVillainNode.DONK, m.donkRead?.valueShare).raise
        else m.villainFirstAction.raise
}
