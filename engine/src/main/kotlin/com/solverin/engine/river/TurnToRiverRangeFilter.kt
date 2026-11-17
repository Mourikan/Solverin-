package com.solverin.engine.river

import com.solverin.engine.flop.Combos
import com.solverin.engine.turn.TurnAction
import com.solverin.engine.turn.TurnContext
import com.solverin.engine.turn.TurnNodeType
import com.solverin.engine.turn.TurnSession
import com.solverin.engine.turn.TurnSpot

/**
 * Bayesian turn → river bridge: w_river(c) = w_turn(c) · P(turn action | c), combos holding the river card removed.
 * Hero's P(a | c) is his solved turn strategy; villain's comes from the turn response partitions.
 *
 * Lines: BARREL_CALL (one bets the turn, the other calls), CHECK_CHECK_TURN, TURN_RAISE_CALL (bet, raise, call),
 * MULTIWAY_3WAY (turn bet called by both opponents).
 */
object TurnToRiverRangeFilter {

    class RiverRanges(
        val hero: FloatArray,
        val villain: FloatArray,
        val extra: FloatArray?,
        val potBb: Double,
        val effectiveStackBb: Double,
        val heroIsAggressor: Boolean,
        val heroInPosition: Boolean,
        val line: RiverLine,
    ) {
        fun spot(turnBoard: IntArray, river: Int, facingBetBb: Double = 0.0, heroInPosition: Boolean = this.heroInPosition): RiverSpot =
            RiverSpot(turnBoard + river, hero, villain, heroInPosition, heroIsAggressor, line, potBb, effectiveStackBb, facingBetBb, extra)
    }

    /** w(c) · P(c), combos holding [river] (or [dead] cards) removed; weights validated in [0, 1]. */
    fun filter(turnRange: FloatArray, actionProbability: FloatArray, river: Int, dead: Long = 0L): FloatArray {
        require(turnRange.size == Combos.COUNT && actionProbability.size == Combos.COUNT) { "Ranges must have ${Combos.COUNT} combos" }
        require(river in 0..51) { "Invalid river card" }
        require(turnRange.all { it.isFinite() && it in 0f..1f }) { "Turn weights must be finite and in [0, 1]" }
        require(actionProbability.all { it.isFinite() && it in -1e-4f..1.0001f }) { "Action probabilities must be finite and in [0, 1]" }
        val gone = dead or (1L shl river)
        return FloatArray(Combos.COUNT) { k -> if (!Combos.isLive(k, gone)) 0f else (turnRange[k] * actionProbability[k].coerceIn(0f, 1f)).coerceIn(0f, 1f) }
    }

    private fun conditional(branch: FloatArray, prior: FloatArray): FloatArray =
        FloatArray(Combos.COUNT) { k -> if (prior[k] <= 0f) 0f else (branch[k] / prior[k]).coerceIn(0f, 1f) }

    fun fromTurn(
        turnSpot: TurnSpot, turnCtx: TurnContext, line: RiverLine, river: Int,
        /** Bet actually made (bb) when it differs from the solved size; hero's P(bet | c) stays the solved strategy. */
        betSizeBb: Double? = null,
        /** Already-built session of [turnSpot] (its 46-river matrix is reused). */
        session: TurnSession? = null,
    ): RiverRanges {
        require(river !in turnSpot.board) { "River card is on the turn board" }
        require((line == RiverLine.MULTIWAY_3WAY) == (turnSpot.extraRange != null)) { "MULTIWAY_3WAY requires a 3-way turn spot (and only it)" }
        val ses = session?.also { require(it.spot === turnSpot) { "Session of another spot" } } ?: TurnSession(turnSpot, turnCtx.policy)
        val a = ses.analyze(turnCtx)
        val m = ses.math(turnCtx)
        val dead = Combos.mask(turnSpot.board)
        val pot = turnSpot.potBb
        val stack = turnSpot.effectiveStackBb
        val heroFreq = { act: TurnAction -> FloatArray(Combos.COUNT) { a.frequency(act, it) } }
        val heroBets = turnSpot.nodeType != TurnNodeType.VS_BET
        fun ranges(hp: FloatArray, vp: FloatArray, xp: FloatArray?, potAfter: Double, invested: Double, heroAgg: Boolean) = RiverRanges(
            filter(m.hero, hp, river, dead), filter(m.villain, vp, river, dead), xp?.let { filter(m.extra!!, it, river, dead) },
            potAfter, (stack - invested).coerceAtLeast(0.0), heroAgg, turnSpot.heroInPosition, line,
        )
        val facing = if (turnSpot.nodeType == TurnNodeType.FIRST_TO_ACT) m.villain else m.villainFirstAction.fold
        when (line) {
            RiverLine.CHECK_CHECK_TURN -> {
                require(heroBets) { "CHECK_CHECK_TURN needs a turn node where hero can check" }
                return ranges(heroFreq(TurnAction.CHECK), conditional(m.villainFirstAction.fold, m.villain), null, pot, 0.0, turnSpot.heroIsAggressor)
            }
            RiverLine.BARREL_CALL, RiverLine.MULTIWAY_3WAY -> {
                if (heroBets) {
                    val b = betSizeBb?.let { minOf(it, stack) } ?: requireNotNull(a.betSizeBb) { "No turn bet available (stack 0)" }
                    val resp = m.villainModel.respondToBet(facing, pot, b)
                    val xp = if (line == RiverLine.MULTIWAY_3WAY) conditional(m.extraModel!!.respondToBet(m.extraNodeRange!!, pot, b).call, m.extra!!) else null
                    return ranges(heroFreq(a.betAction!!), conditional(resp.call, m.villain), xp, pot + b * (2 + if (xp != null) 1 else 0), b, heroAgg = true)
                }
                val bet = minOf(turnSpot.facingBetBb, stack)
                val xp = if (line == RiverLine.MULTIWAY_3WAY) conditional(m.extraModel!!.respondToBet(m.extraNodeRange!!, pot + bet, bet).call, m.extra!!) else null
                return ranges(heroFreq(TurnAction.CALL), conditional(m.villainFirstAction.raise, m.villain), xp, pot + bet * (2 + if (xp != null) 1 else 0), bet, heroAgg = false)
            }
            RiverLine.TURN_RAISE_CALL -> {
                if (!heroBets) {
                    val bet = minOf(turnSpot.facingBetBb, stack)
                    val betRange = m.villainFirstAction.raise
                    val to = m.raiseTo(bet, pot + bet)
                    return ranges(heroFreq(TurnAction.RAISE), conditional(m.villainModel.respondToRaise(betRange).call, m.villain), null, pot + 2 * to, to, heroAgg = true)
                }
                val b = betSizeBb?.let { minOf(it, stack) } ?: requireNotNull(a.betSizeBb) { "No turn bet available (stack 0)" }
                val resp = m.villainModel.respondToBet(facing, pot, b)
                val y = m.raiseTo(b, pot + b)
                val bet = heroFreq(a.betAction!!)
                val hp = FloatArray(Combos.COUNT) { h ->
                    if (bet[h] <= 0f || m.hero[h] <= 0f) 0f
                    else if (m.reraiseResponse(h, pot, b, 0.0, 0.0, resp, 1).best == TurnAction.CALL) bet[h] else 0f
                }
                return ranges(hp, conditional(resp.raise, m.villain), null, pot + 2 * y, y, heroAgg = false)
            }
        }
    }
}
