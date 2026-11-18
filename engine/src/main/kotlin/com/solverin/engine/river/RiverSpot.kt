package com.solverin.engine.river

import com.solverin.engine.core.RakeStructure
import com.solverin.engine.core.Stake
import com.solverin.engine.flop.Combos
import com.solverin.engine.math.RakeAttribution

/** How the turn was played before this river. */
enum class RiverLine { BARREL_CALL, CHECK_CHECK_TURN, TURN_RAISE_CALL, MULTIWAY_3WAY }

enum class RiverNodeType { FIRST_TO_ACT, VS_CHECK, VS_BET }

/** River actions; hero's aggressive first action is labelled by context. */
enum class RiverAction { CHECK, BET, TRIPLE_BARREL, DELAYED_BET, PROBE, DONK, FOLD, CALL, RAISE }

/** Villain river lead read: weak block (≤ weakDonkSize) vs polarized big lead on a completing card. */
enum class RiverDonkType { WEAK, POLARIZED }

/**
 * River spot: 5-card board, filtered river ranges ([TurnToRiverRangeFilter]). [heroIsAggressor] = hero made the last
 * turn aggression (or holds the initiative after a checked turn). [potBb] excludes [facingBetBb].
 */
class RiverSpot(
    val board: IntArray,
    val heroRange: FloatArray,
    val villainRange: FloatArray,
    val heroInPosition: Boolean,
    val heroIsAggressor: Boolean,
    val line: RiverLine,
    val potBb: Double,
    val effectiveStackBb: Double,
    val facingBetBb: Double = 0.0,
    val extraRange: FloatArray? = null,
    /** The pot was 3-bet (or more) preflop: villain's range is condensed. */
    val threeBetPot: Boolean = false,
) {
    /** Villain called hero's turn barrel (two streets of calls). */
    val villainCalledTwoStreets: Boolean get() = (line == RiverLine.BARREL_CALL || line == RiverLine.TURN_RAISE_CALL) && heroIsAggressor
    /** Villain's range is condensed: 3-bet+ pot, a turn raise / call line, or he called hero's turn barrel. */
    val condensedVillain: Boolean get() = threeBetPot || line == RiverLine.TURN_RAISE_CALL || villainCalledTwoStreets

    init {
        require(board.size == 5 && board.toSet().size == 5 && board.all { it in 0..51 }) { "River board needs 5 distinct cards" }
        require(heroRange.size == Combos.COUNT && villainRange.size == Combos.COUNT) { "Ranges must have ${Combos.COUNT} combos" }
        require(heroRange.all { it.isFinite() && it in 0f..1f }) { "Hero combo weights must be finite and in [0, 1]" }
        require(villainRange.all { it.isFinite() && it in 0f..1f }) { "Villain combo weights must be finite and in [0, 1]" }
        extraRange?.let { x -> require(x.size == Combos.COUNT && x.all { it.isFinite() && it in 0f..1f }) { "Extra combo weights must be finite and in [0, 1]" } }
        require(potBb.isFinite() && potBb > 0) { "Pot must be > 0" }
        require(effectiveStackBb.isFinite() && effectiveStackBb >= 0) { "Stack must be >= 0" }
        require(facingBetBb.isFinite() && facingBetBb >= 0) { "Facing bet must be >= 0" }
        require((line == RiverLine.MULTIWAY_3WAY) == (extraRange != null)) { "MULTIWAY_3WAY requires an extra range (and only it)" }
    }

    val turnBoard: IntArray get() = board.copyOfRange(0, 4)
    val riverCard: Int get() = board[4]
    val multiway: Boolean get() = extraRange != null

    val nodeType: RiverNodeType get() = when {
        facingBetBb > 0.0 -> RiverNodeType.VS_BET
        heroInPosition -> RiverNodeType.VS_CHECK
        else -> RiverNodeType.FIRST_TO_ACT
    }

    val heroBetAction: RiverAction get() = when {
        heroIsAggressor && line == RiverLine.CHECK_CHECK_TURN -> RiverAction.DELAYED_BET
        heroIsAggressor -> RiverAction.TRIPLE_BARREL
        !heroInPosition && line == RiverLine.CHECK_CHECK_TURN -> RiverAction.PROBE
        !heroInPosition -> RiverAction.DONK
        else -> RiverAction.BET
    }

    /** Villain leads OOP into the turn aggressor (river donk). */
    val villainDonks: Boolean get() = heroInPosition && heroIsAggressor && line != RiverLine.CHECK_CHECK_TURN
    val isDonk: Boolean get() = nodeType == RiverNodeType.VS_BET && villainDonks
}

/** Villain river decision points (node-lockable). */
enum class RiverVillainNode { VS_BET, VS_OVERBET, VS_RAISE, VS_JAM, BET, DONK, RIVER_ELASTICITY }

/**
 * Villain river tendencies (population). foldToRiverBet at 66% pot, foldToRiverOverbet at 150%; fold vs smaller bets
 * scales linearly to 0 at size 0. betValueShare: value share of his river bets (1 − bluff frequency).
 */
data class RiverProfile(
    val foldToRiverBet: Double, val foldToRiverOverbet: Double,
    val raiseVsBet: Double, val raiseValueShare: Double,
    val foldToRiverRaise: Double, val reraiseVsRaise: Double, val callJam: Double,
    val barrel: Double, val delayedBet: Double, val probe: Double, val stab: Double, val donk: Double,
    val betValueShare: Double, val riverCallElasticity: Double,
    val betSizeFraction: Double = 0.66,
    val microBetMaxFold: Double = 0.08,
    val microBetValueShare: Double = 0.30,
) {
    companion object {
        fun population(stake: Stake): RiverProfile = when (stake) {
            Stake.NL2, Stake.NL5 -> RiverProfile(.35, .45, .05, .92, .40, .04, .70, .45, .40, .35, .40, .08, .72, .75)
            Stake.NL10, Stake.NL25 -> RiverProfile(.40, .50, .06, .90, .45, .05, .62, .48, .42, .36, .40, .07, .68, .65)
            Stake.NL50, Stake.NL100, Stake.NL200 -> RiverProfile(.43, .53, .07, .88, .48, .06, .55, .50, .45, .38, .42, .06, .65, .55)
        }
    }
}

data class RiverNodeLock(
    val fold: Double? = null,
    val raise: Double? = null,
    val frequency: Double? = null,
    val valueShare: Double? = null,
) {
    init {
        for (v in listOfNotNull(fold, raise, frequency, valueShare)) require(v.isFinite() && v in 0.0..1.0) { "Lock values must be in [0, 1]" }
        require((fold ?: 0.0) + (raise ?: 0.0) <= 1.0 + 1e-9) { "fold + raise > 1" }
    }
}

data class RiverNodeLocks(val locks: Map<RiverVillainNode, RiverNodeLock> = emptyMap()) {
    operator fun get(n: RiverVillainNode): RiverNodeLock? = locks[n]
    fun with(n: RiverVillainNode, l: RiverNodeLock) = RiverNodeLocks(locks + (n to l))
    operator fun plus(o: RiverNodeLocks) = RiverNodeLocks(locks + o.locks)

    companion object {
        val NONE = RiverNodeLocks()
        fun foldToRiverBet(f: Double) = NONE.with(RiverVillainNode.VS_BET, RiverNodeLock(fold = f))
        fun foldToRiverOverbet(f: Double) = NONE.with(RiverVillainNode.VS_OVERBET, RiverNodeLock(fold = f))
        fun foldToRiverRaise(f: Double) = NONE.with(RiverVillainNode.VS_RAISE, RiverNodeLock(fold = f))
        /** Bluff share of villain's river bets (value share = 1 − f), leads included. */
        fun riverBluffFrequency(f: Double) = NONE.with(RiverVillainNode.BET, RiverNodeLock(valueShare = 1 - f))
            .with(RiverVillainNode.DONK, RiverNodeLock(valueShare = 1 - f))
        fun riverDonkFreq(freq: Double) = NONE.with(RiverVillainNode.DONK, RiverNodeLock(frequency = freq))
        fun riverCallElasticity(e: Double) = NONE.with(RiverVillainNode.RIVER_ELASTICITY, RiverNodeLock(frequency = e))
    }
}

data class RiverPolicy(
    val blockBet: Double = 0.25,
    val thinBet: Double = 0.33,
    val standardBet: Double = 0.70,
    val overbetLow: Double = 1.25,
    val overbetHigh: Double = 1.5,
    val overbetMax: Double = 2.0,
    /** Overbets / jam need ΔNuts_river above this or a capped villain range. */
    val overbetNutEdge: Double = 0.05,
    val cappedNutShare: Double = 0.04,
    /** ΔNuts_river threshold (showdown equity). */
    val nutEquity: Float = 0.85f,
    /** B_nut reference: villain combos with ≥ this equity vs hero's range (nuts / calls). */
    val blockerNutEquity: Float = 0.75f,
    /** B_fold reference: villain combos below this equity (missed draws / air). */
    val blockerFoldEquity: Float = 0.25f,
    /** Overbet value must be ≥ this vs villain's whole range (pure nuts). */
    val overbetValueEquity: Float = 0.85f,
    /** Block / thin sizes: value region of EQ_total. */
    val blockValueLow: Float = 0.58f,
    val blockValueHigh: Float = 0.75f,
    /** Sub-equation 2: thin value needs EQ_SD vs the calling range ≥ thinValueEquity. */
    val thinValueEquity: Float = 0.52f,
    val showdownLow: Float = 0.35f,
    val showdownHigh: Float = 0.65f,
    val raiseMultiplier: Double = 3.0,
    val raisePotFraction: Double = 0.75,
    val minBetRatio: Double = 0.2,
    val mixBandBb: Double = 0.05,
    val maxBluffRatio: Double = 3.0,
    val maxBluffRatioLocked: Double = 20.0,
    val minBluffRatio: Double = 0.1,
    val stationFold: Double = 0.25,
    val stackOffBluffMarginBb: Double = 1.0,
    val checkRaiseCap: Double = 0.25,
    val weakDonkMdfSlack: Double = 0.05,
    val weakDonkSize: Double = 0.35,
    val polarDonkSize: Double = 0.75,
    val weakDonkValueShare: Double = 0.35,
    val polarDonkValueShare: Double = 0.85,
    /** Non-value part of block / micro bets taken from weak made hands (rest: missed draws / air). */
    val weakBetMadeShare: Double = 0.7,
    /** Entry-line condensation of villain's fold vs a river raise / jam. */
    val condensationCheckCheck: Double = 1.0,
    val condensationBarrelCall: Double = 0.9,
    val condensationMultiway: Double = 0.85,
    val condensationRaiseCall: Double = 0.6,
    val raiseFoldReferenceOdds: Double = 0.27,
    /** Committed band: villain bet ≥ committedBetSize, TURN_RAISE_CALL or SPR ≤ committedSpr. */
    val committedBetSize: Double = 0.75,
    val committedSpr: Double = 1.5,
    val committedFoldMin: Double = 0.15,
    val committedFoldMax: Double = 0.25,
    val committedOddsLow: Double = 0.10,
    val committedOddsHigh: Double = 0.35,
    /**
     * PostflopMassiveExploitOverride: B_nut < exploitBlocker and EV − passive ≥ max(exploitOverrideBb, exploitPotShare × pot);
     * off when the villain called two streets without over-folding the river (condensed range).
     */
    val exploitOverrideBb: Double = 4.0,
    val exploitPotShare: Double = 0.25,
    val exploitBlocker: Double = 0.75,
    /** Condensed villain: bluff-cap ratio × this, P(fold) × condensedFoldScale, all-in needs stackOffEquity vs the call range. */
    val condensedBluffScale: Double = 0.35,
    val condensedFoldScale: Double = 0.65,
    val stackOffEquity: Float = 0.62f,
    /** Real over-fold lock: P(fold) ≥ (1 − MDF) + this, or the cap is never bypassed. */
    val overrideOverfoldMargin: Double = 0.08,
    /** A villain who called flop + turn folds the river at P(fold) × this (bluffs). */
    val persistenceTightening: Double = 0.82,
    /** Thin value vs a range that called two streets: a bet needs this equity vs the calling range, else check. */
    val thinValueVsCallers: Float = 0.54f,
    /** Four-flush / four-straight shield. */
    val fourCardShield: Boolean = true,
)

data class RiverContext(
    val stake: Stake,
    val rake: RakeStructure = stake.defaultRake,
    val rakeAttribution: RakeAttribution = RakeAttribution.WINNER_SHARE,
    val profile: RiverProfile = RiverProfile.population(stake),
    val locks: RiverNodeLocks = RiverNodeLocks.NONE,
    val policy: RiverPolicy = RiverPolicy(),
)

/**
 * Terminal river EV terms for one hero combo / action (bb, relative to folding now):
 * EV = P(fold)·(Pot + Dead) + P(call)·[EQ_SD·(Pot + 2s) − s − Rake] + P(raise)·EV(ReraiseResponse)
 * R_river = 1, implied = 0, RIO = 0 by construction. Identity: ev = pFold·foldWin + pCall·calledEv + pRaise·evVsRaise.
 */
data class RiverEvTerms(
    val pFold: Double,
    val pCall: Double,
    val pRaise: Double,
    val foldWin: Double,
    val equity: Float,
    val potTotal: Double,
    val investment: Double,
    val rake: Double,
    val evVsRaise: Double,
    val calledEv: Double,
    val allIn: Boolean,
    val ev: Double,
    val sizeBb: Double = 0.0,
    val defenseShare: Double = Double.NaN,
    val bNut: Double = 1.0,
    val bFold: Double = 1.0,
) {
    val realization: Float get() = 1f
    val impliedRiver: Double get() = 0.0
    val rioPenalty: Double get() = 0.0
}
