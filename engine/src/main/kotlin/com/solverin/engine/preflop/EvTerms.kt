package com.solverin.engine.preflop

/**
 * Terms of the master equation for one action and hand (bb):
 * EV = pFold*(pot + dead) + pCall*[EQ*R*potTotal - investment - rake] + pReraise*evVsReraise + implied
 * For multi-opponent raises the probabilities aggregate all opponents; equity/realization/potTotal/rake
 * describe the branch where the first opponent calls.
 */
data class EvTerms(
    val pFold: Double,
    val pCall: Double,
    val pReraise: Double,
    val foldWin: Double,
    val equity: Float,
    val realization: Float,
    val potTotal: Double,
    val investment: Double,
    val rake: Double,
    val implied: Double,
    val evVsReraise: Double,
    val ev: Double,
) {
    /** Postflop branch value when called / when flatting. */
    val calledBranch: Double get() = equity * realization * potTotal - investment - rake + implied
}
