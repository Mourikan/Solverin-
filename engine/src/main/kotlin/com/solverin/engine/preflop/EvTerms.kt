package com.solverin.engine.preflop

/**
 * Terms of the master equation for one action and hand (bb):
 *
 * EV = P(fold)·(Pot + Dead) + P(call)·[EQ·R·PotTotal − Investment − Rake] + P(reraise)·EV_vsReraise + EV_implied
 *
 * Raises vs several players: probabilities aggregate all opponents (first continuer plays heads-up);
 * fold-model, equity, realization, pot, rake and implied describe the first opponent's called branch.
 * Calls: pReraise = P(squeeze behind), evVsReraise = −Investment.
 */
data class EvTerms(
    val pFold: Double,
    val pCall: Double,
    val pReraise: Double,
    val foldWin: Double,
    val mdf: Double,
    val blockerFactor: Double,
    val tendency: Double,
    val equity: Float,
    val basePos: Double,
    val initiative: Double,
    val playability: Double,
    val domination: Double,
    val realization: Float,
    val realizationNoDomination: Float,
    val potTotal: Double,
    val investment: Double,
    val rake: Double,
    val implied: Double,
    val evVsReraise: Double,
    val ev: Double,
) {
    /** Called-branch value: EQ·R·PotTotal − Investment − Rake + EV_implied. */
    val calledBranch: Double get() = minOf(equity * realization, PreflopMathEngine.MAX_EQR) * potTotal - investment - rake + implied
}
