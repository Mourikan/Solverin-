package com.solverin.engine.preflop

/**
 * Terms of the master equation for one action and hand (bb):
 *
 * EV = P(fold)·(Pot + Dead) + P(call)·[EQ·R·PotTotal − Investment − Rake] + P(reraise)·EV_vsReraise + EV_implied
 *
 * Raises vs several players: probabilities aggregate all opponents (first continuer plays heads-up);
 * fold-model, equity, realization, pot, rake and implied describe the MAIN opponent's called branch;
 * EV = pFold·foldWin + pCall·calledEv + pReraise·evVsReraise holds exactly.
 * realization is the effective R (multiway overcall loss included).
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
    /** Called-branch EV averaged over every opponent that can call (weights = P(that opponent is the caller)). */
    val calledEv: Double,
    val ev: Double,
) {
    /** Main villain's own P(fold) = 1 − MDF·BF·TM (card removal applied). */
    val mainFold: Double get() = (1.0 - mdf * blockerFactor * tendency).coerceIn(0.0, 1.0)

    /** Main villain over-folds vs MDF by [margin]: P(fold) > 1 − MDF + margin. */
    fun overfolds(margin: Double = 0.0): Boolean = mdf > 0.0 && mainFold > 1.0 - mdf + margin

    /** Called-branch value: EQ·R·PotTotal − Investment − Rake + EV_implied. */
    val calledBranch: Double get() = minOf(equity * realization, PreflopMathEngine.MAX_EQR) * potTotal - investment - rake + implied
}
