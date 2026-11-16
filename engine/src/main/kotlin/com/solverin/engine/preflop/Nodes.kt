package com.solverin.engine.preflop

import com.solverin.engine.core.Position
import com.solverin.engine.model.Partition
import com.solverin.engine.range.SpotKey

/** Villain acting after hero's raise. */
class Opponent(
    val position: Position,
    /** Response to hero's raise (absolute class weights). */
    val response: Partition,
    /** Chips already in the pot. */
    val invested: Double,
    /** Total bet if villain re-raises (>= stack => jam). */
    val reraiseTo: Double,
    /** Villain's re-raising range split vs hero's next raise (fold / call / jam); null = hero cannot re-raise. */
    val heroReRaise: Partition?,
    /** Total of hero's next raise (>= stack => jam). */
    val heroReRaiseTo: Double,
    val heroInPosition: Boolean,
    /** Hero's own decision node if this villain re-raises (backward induction); null = priced in place. */
    val childKey: SpotKey? = null,
    /** P(a player left to act squeezes after this villain flats) — hero then folds. */
    val squeezeBehind: Double = 0.0,
    /** P(a player left to act overcalls after this villain flats) — pot goes multiway. */
    val overcallBehind: Double = 0.0,
)

/** Hero's aggressive option. All amounts in bb, totals per player. */
class RaiseNode(
    val potBefore: Double,
    val heroInvested: Double,
    val raiseTo: Double,
    /** In acting order; first continuer plays heads-up. */
    val opponents: List<Opponent>,
    /** Pot level if hero's raise is called (1 = single-raised, 2 = 3bet pot, ...). Re-raise => level + 1. */
    val potLevel: Int,
)

/** Hero's passive option (flat / overcall). */
class CallNode(
    val potBefore: Double,
    val heroInvested: Double,
    val callTo: Double,
    /** Ranges hero faces postflop (1 = heads-up, 2 = multiway). */
    val villainRanges: List<FloatArray>,
    val heroInPosition: Boolean,
    /** P(a player behind squeezes) — hero folds the flat. */
    val squeezeRisk: Double,
    /** Pot level after the flat (1 = single-raised, 2 = 3bet pot, ...). */
    val potLevel: Int,
)

class SpotNodes(val call: CallNode?, val raise: RaiseNode?) {
    init { require(call != null || raise != null) }
}
