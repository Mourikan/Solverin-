package com.solverin.engine.preflop

import com.solverin.engine.core.Position
import com.solverin.engine.model.Partition

/** Villain acting after hero's raise. */
class Opponent(
    val position: Position,
    /** Response to hero's raise (absolute class weights). */
    val response: Partition,
    /** Chips already in the pot. */
    val invested: Double,
    /** Total bet if villain re-raises (>= stack => jam). */
    val reraiseTo: Double,
    /** Part of response.raise that continues vs a hero jam; null = no jam option. */
    val continuesVsJam: FloatArray?,
    val heroInPosition: Boolean,
)

/** Hero's aggressive option. All amounts in bb, totals per player. */
class RaiseNode(
    val potBefore: Double,
    val heroInvested: Double,
    val raiseTo: Double,
    /** In acting order; first continuer plays heads-up. */
    val opponents: List<Opponent>,
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
)

class SpotNodes(val call: CallNode?, val raise: RaiseNode)
