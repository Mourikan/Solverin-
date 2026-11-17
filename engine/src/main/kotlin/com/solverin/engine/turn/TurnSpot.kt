package com.solverin.engine.turn

import com.solverin.engine.core.RakeStructure
import com.solverin.engine.core.Stake
import com.solverin.engine.flop.Combos
import com.solverin.engine.math.RakeAttribution

/** How the flop was played before this turn. */
enum class FlopLine { BET_CALL, CHECK_CHECK, RAISE_CALL, MULTIWAY_3WAY }

enum class TurnNodeType { FIRST_TO_ACT, VS_CHECK, VS_BET }

/** Turn actions; the aggressive first action is labelled by context (barrel, probe, delayed c-bet, donk, stab). */
enum class TurnAction { CHECK, BET, SECOND_BARREL, PROBE, DELAYED_CBET, DONK, FOLD, CALL, RAISE }

/**
 * Turn spot. [board] = 3 flop cards + turn card. Ranges are the filtered turn ranges (see
 * [FlopToTurnRangeFilter]). [heroIsAggressor] = hero holds the initiative entering the turn (last bettor
 * on the flop, or preflop aggressor after a checked flop). [potBb] excludes [facingBetBb].
 */
class TurnSpot(
    val board: IntArray,
    val heroRange: FloatArray,
    val villainRange: FloatArray,
    val heroInPosition: Boolean,
    val heroIsAggressor: Boolean,
    val line: FlopLine,
    val potBb: Double,
    val effectiveStackBb: Double,
    val facingBetBb: Double = 0.0,
    val extraRange: FloatArray? = null,
) {
    init {
        require(board.size == 4 && board.toSet().size == 4 && board.all { it in 0..51 }) { "Turn board needs 4 distinct cards" }
        require(heroRange.size == Combos.COUNT && villainRange.size == Combos.COUNT) { "Ranges must have ${Combos.COUNT} combos" }
        require(heroRange.all { it.isFinite() && it in 0f..1f }) { "Hero combo weights must be finite and in [0, 1]" }
        require(villainRange.all { it.isFinite() && it in 0f..1f }) { "Villain combo weights must be finite and in [0, 1]" }
        extraRange?.let { x ->
            require(x.size == Combos.COUNT && x.all { it.isFinite() && it in 0f..1f }) { "Extra combo weights must be finite and in [0, 1]" }
        }
        require(potBb.isFinite() && potBb > 0) { "Pot must be > 0" }
        require(effectiveStackBb.isFinite() && effectiveStackBb >= 0) { "Stack must be >= 0" }
        require(facingBetBb.isFinite() && facingBetBb >= 0) { "Facing bet must be >= 0" }
        require((line == FlopLine.MULTIWAY_3WAY) == (extraRange != null)) { "MULTIWAY_3WAY requires an extra range (and only it)" }
    }

    val flop: IntArray get() = board.copyOfRange(0, 3)
    val turnCard: Int get() = board[3]
    val multiway: Boolean get() = extraRange != null

    val nodeType: TurnNodeType get() = when {
        facingBetBb > 0.0 -> TurnNodeType.VS_BET
        heroInPosition -> TurnNodeType.VS_CHECK
        else -> TurnNodeType.FIRST_TO_ACT
    }

    /** Label of hero's aggressive first action at this node. */
    val heroBetAction: TurnAction get() = when {
        heroIsAggressor && line == FlopLine.CHECK_CHECK -> TurnAction.DELAYED_CBET
        heroIsAggressor -> TurnAction.SECOND_BARREL
        !heroInPosition && line == FlopLine.CHECK_CHECK -> TurnAction.PROBE
        !heroInPosition -> TurnAction.DONK
        else -> TurnAction.BET
    }

    /** Villain's first action (when he acts / acted before hero's decision or after hero checks). */
    val villainBetNode: TurnVillainNode get() = when {
        heroInPosition -> when {
            heroIsAggressor && line == FlopLine.CHECK_CHECK -> TurnVillainNode.PROBE
            heroIsAggressor -> TurnVillainNode.DONK
            line == FlopLine.CHECK_CHECK -> TurnVillainNode.DELAYED_CBET
            else -> TurnVillainNode.BARREL
        }
        heroIsAggressor -> TurnVillainNode.STAB
        line == FlopLine.CHECK_CHECK -> TurnVillainNode.DELAYED_CBET
        else -> TurnVillainNode.BARREL
    }

    /** Villain's response node to hero's aggressive first action. */
    val villainResponseNode: TurnVillainNode get() = when (heroBetAction) {
        TurnAction.SECOND_BARREL -> TurnVillainNode.VS_SECOND_BARREL
        TurnAction.DELAYED_CBET -> TurnVillainNode.VS_DELAYED_CBET
        TurnAction.PROBE -> TurnVillainNode.VS_PROBE
        TurnAction.DONK -> TurnVillainNode.VS_DONK
        else -> TurnVillainNode.VS_BET
    }

    val isDonk: Boolean get() = nodeType == TurnNodeType.VS_BET && villainBetNode == TurnVillainNode.DONK
}

/** Villain turn decision points (node-lockable). */
enum class TurnVillainNode {
    VS_SECOND_BARREL, VS_DELAYED_CBET, VS_PROBE, VS_DONK, VS_BET, VS_TURN_RAISE, VS_JAM,
    BARREL, DELAYED_CBET, PROBE, DONK, STAB, RIVER_ELASTICITY,
}

/**
 * Villain turn tendencies (population priors). fold* at the 66%-pot reference size, rescaled by MDF.
 * riverCallElasticity: share of the pot-sized river extraction villain pays when hero hits (implied odds).
 */
data class TurnProfile(
    val foldToSecondBarrel: Double, val foldToDelayedCbet: Double, val foldToProbe: Double,
    val foldToDonk: Double, val foldToBet: Double,
    val raiseVsBet: Double, val foldToRaise: Double, val reraiseVsRaise: Double, val callJam: Double,
    val barrel: Double, val delayedCbet: Double, val probe: Double, val donk: Double, val stab: Double,
    val betValueShare: Double, val raiseValueShare: Double,
    val riverCallElasticity: Double,
    val betSizeFraction: Double = 0.66,
    val microBetMaxFold: Double = 0.10,
) {
    companion object {
        fun population(stake: Stake): TurnProfile = when (stake) {
            Stake.NL2, Stake.NL5 -> TurnProfile(.38, .40, .36, .33, .38, .06, .45, .05, .70, .50, .40, .35, .10, .35, .75, .85, .75)
            Stake.NL10, Stake.NL25 -> TurnProfile(.42, .43, .38, .36, .41, .07, .50, .06, .62, .54, .43, .36, .08, .38, .70, .80, .65)
            Stake.NL50, Stake.NL100, Stake.NL200 -> TurnProfile(.45, .45, .40, .38, .43, .08, .55, .07, .55, .56, .45, .38, .06, .40, .65, .75, .55)
        }
    }
}

/** Locked turn frequencies; null keeps the profile. fold / raise for response nodes, frequency / valueShare for bets. */
data class TurnNodeLock(
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

data class TurnNodeLocks(val locks: Map<TurnVillainNode, TurnNodeLock> = emptyMap()) {
    operator fun get(n: TurnVillainNode): TurnNodeLock? = locks[n]
    fun with(n: TurnVillainNode, l: TurnNodeLock) = TurnNodeLocks(locks + (n to l))

    companion object {
        val NONE = TurnNodeLocks()
        fun foldToSecondBarrel(f: Double) = NONE.with(TurnVillainNode.VS_SECOND_BARREL, TurnNodeLock(fold = f))
        fun foldToProbe(f: Double) = NONE.with(TurnVillainNode.VS_PROBE, TurnNodeLock(fold = f))
        fun foldToTurnRaise(f: Double) = NONE.with(TurnVillainNode.VS_TURN_RAISE, TurnNodeLock(fold = f))
        fun turnDonkFrequency(freq: Double, valueShare: Double? = null) =
            NONE.with(TurnVillainNode.DONK, TurnNodeLock(frequency = freq, valueShare = valueShare))
        /** riverCallElasticity lock: frequency = share of river extraction villain pays. */
        fun riverCallElasticity(e: Double) = NONE.with(TurnVillainNode.RIVER_ELASTICITY, TurnNodeLock(frequency = e))
    }
}

/** Turn model tunables. */
data class TurnPolicy(
    val smallBet: Double = 0.33,
    val standardBet: Double = 0.70,
    val overbetLow: Double = 1.25,
    val overbetHigh: Double = 1.5,
    /** Overbets are candidates only when ΔNuts_turn exceeds this and villain's range is capped. */
    val overbetNutEdge: Double = 0.06,
    /** Villain is capped when his share of > nutEquity combos is below this. */
    val cappedNutShare: Double = 0.04,
    val raiseMultiplier: Double = 3.0,
    val raisePotFraction: Double = 0.75,
    val minBetRatio: Double = 0.2,
    val commitSpr: Double = 0.5,
    val mixBandBb: Double = 0.05,
    val nutEquity: Double = 0.85,
    val strongFraction: Double = 0.15,
    val posIp: Double = 1.0,
    val posOop: Double = 0.92,
    val initiativeAggressor: Double = 1.02,
    val initiativeCaller: Double = 0.98,
    /** f(SPR) = villainOutsWeight · min(1, SPR / 2): weight of villain clean outs in R_turn. */
    val villainOutsWeight: Double = 0.5,
    val lowSpr: Double = 1.0,
    /** LeverageFactor α_pos (IP / OOP); the factor is clamped to [1, maxLeverage]. */
    val leverageAlphaIp: Double = 1.0,
    val leverageAlphaOop: Double = 0.7,
    val maxLeverage: Double = 1.25,
    /** Clean out: EQ_turn < 0.5 and EQ_river ≥ cleanRiverEquity. */
    val cleanRiverEquity: Double = 0.80,
    /** Dirty out: hero improves category, dirtyLow ≤ EQ_river < dirtyHigh, villain improved-and-ahead share ≥ dirtyNutShare. */
    val dirtyLow: Double = 0.35,
    val dirtyHigh: Double = 0.70,
    val dirtyNutShare: Double = 0.15,
    /** Villain clean out (hero ahead on the turn): EQ_river below this. */
    val villainOutEquity: Double = 0.40,
    /** River nut threshold for ΔNuts_river and river nut blockers. */
    val riverNutEquity: Double = 0.85,
    /** Scare card: ΔNuts_river exceeds the mean over the 46 rivers by at least this. */
    val scareNutShift: Double = 0.03,
    /** Sizing_river_opt: hero's river value bet (pot fraction). */
    val riverValueBet: Double = 0.75,
    /** P(Payoff | Pos) multiplier OOP (IP = 1). */
    val payoffOop: Double = 0.8,
    val semiBluffOuts: Int = 8,
    val bluffBaseMin: Double = 0.45,
    val bluffBaseMax: Double = 0.65,
    val maxBluffRatio: Double = 3.0,
    val maxBluffRatioLocked: Double = 20.0,
    /** A node-locked fold at or below this marks a calling station: pure bluffs → 0, thin value allowed. */
    val stationFold: Double = 0.25,
    val thinValueEquity: Float = 0.45f,
    val checkRaiseCapConnected: Double = 0.20,
    val checkRaiseCap: Double = 0.28,
    val stackOffBluffMarginBb: Double = 1.0,
    val weakDonkMdfSlack: Double = 0.05,
)

data class TurnContext(
    val stake: Stake,
    val rake: RakeStructure = stake.defaultRake,
    val rakeAttribution: RakeAttribution = RakeAttribution.WINNER_SHARE,
    val profile: TurnProfile = TurnProfile.population(stake),
    val locks: TurnNodeLocks = TurnNodeLocks.NONE,
    val policy: TurnPolicy = TurnPolicy(),
)

/**
 * Master turn EV equation terms for one hero combo / action (bb, relative to folding now):
 * EV = P(fold)·Pot + P(call)·[EQ46·R_turn·PotTotal − Inv − Rake + EV_implied_river − Penalty_RIO] + P(raise)·EV(reraise)
 * calledEv contains implied / RIO; identity: ev = pFold·foldWin + pCall·calledEv + pRaise·evVsRaise.
 */
data class TurnEvTerms(
    val pFold: Double,
    val pCall: Double,
    val pRaise: Double,
    val foldWin: Double,
    val equity: Float,
    val realization: Float,
    val potTotal: Double,
    val investment: Double,
    val rake: Double,
    val impliedRiver: Double,
    val rioPenalty: Double,
    val evVsRaise: Double,
    val calledEv: Double,
    val allIn: Boolean,
    val ev: Double,
    /** Drawing hand calling below direct pot odds (needs implied odds to continue). */
    val drawNeedsImplied: Boolean = false,
)
