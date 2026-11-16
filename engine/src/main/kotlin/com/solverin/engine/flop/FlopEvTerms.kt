package com.solverin.engine.flop

/**
 * Master flop EV equation for one hero combo and action (bb, relative to folding now):
 *
 * EV = P(fold)·(Pot + Dead) + P(call)·[EQ·R_post·PotTotal − Inv − Rake_flop] + P(raise)·EV_vsRaise
 *      + EV_implied − Penalty_texture
 *
 * The called-branch EV_implied is inside [calledEv]; [implied] reports it. For CHECK: pCall = P(check
 * through), pRaise = P(villain bets), evVsRaise = hero's best response to that bet.
 * For CALL: pCall = 1. Identity: ev = pFold·foldWin + pCall·calledEv + pRaise·evVsRaise − penalty.
 */
data class FlopEvTerms(
    val pFold: Double,
    val pCall: Double,
    val pRaise: Double,
    val foldWin: Double,
    val equity: Float,
    val realization: Float,
    val potTotal: Double,
    val investment: Double,
    val rake: Double,
    val implied: Double,
    val penalty: Double,
    val evVsRaise: Double,
    val calledEv: Double,
    val allIn: Boolean,
    val ev: Double,
)
