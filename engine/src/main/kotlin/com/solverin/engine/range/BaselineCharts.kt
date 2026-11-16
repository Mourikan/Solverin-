package com.solverin.engine.range

import com.solverin.engine.core.Position
import com.solverin.engine.core.Position.*

/**
 * Baseline: 6-max, 100bb, opens 2.5bb (SB 3bb), Nash-approximate.
 * Rake reference = Stake.REFERENCE_RAKE. Dynamic stake/rake/exploit deviations
 * are applied on top by ExploitAdjuster. Edit freely: notation -> RangeParser.
 */
object BaselineCharts {

    val RFI: Map<Position, ChartSpec> = mapOf(
        UTG to ChartSpec(
            value = "88+,ATs+,KQs,AJo+",
            bluff = "77-22,A9s-A2s,KTs+,QTs+,JTs,T9s,98s,87s:0.5,76s:0.5,65s:0.5,KQo,ATo:0.7",
        ),
        HJ to ChartSpec(
            value = "77+,ATs+,KJs+,AJo+,KQo",
            bluff = "66-22,A9s-A2s,K8s-KTs,Q9s+,J9s+,T8s+,97s+,86s+,75s+,65s,54s:0.5,ATo,KJo,QJo:0.6",
        ),
        CO to ChartSpec(
            value = "66+,A9s+,KTs+,QJs,ATo+,KJo+",
            bluff = "55-22,A8s-A2s,K5s-K9s,Q7s+,J7s+,T7s+,96s+,86s+,75s+,64s+,54s,43s:0.3,A7o-A9o,A5o:0.5,KTo,K9o,Q9o+,J9o+,T9o",
        ),
        BTN to ChartSpec(
            value = "55+,A8s+,K9s+,QTs+,JTs,ATo+,KTo+,QJo",
            bluff = "44-22,A7s-A2s,K2s-K8s,Q4s-Q9s,J5s-J9s,T6s+,95s+,84s+,74s+,63s+,53s+,43s,A2o-A9o,K7o-K9o,Q8o-QTo,J8o+,T8o+,97o+,87o,76o:0.5,65o:0.3",
        ),
        SB to ChartSpec(
            value = "66+,A9s+,KTs+,QJs,ATo+,KJo+",
            bluff = "55-22,A8s-A2s,K3s-K9s,Q5s+,J7s+,T7s+,96s+,86s+,75s+,64s+,54s,43s:0.5,A4o-A9o,K8o-KTo,Q9o+,J9o+,T9o",
        ),
    )

    /** Key = hero to opener. */
    val VS_RFI: Map<Pair<Position, Position>, ChartSpec> = mapOf(
        (HJ to UTG) to ChartSpec("QQ+,AKs,AKo:0.8", "A5s:0.7,A4s:0.5,KJs:0.3,QJs:0.2,76s:0.2",
            "JJ,TT,99:0.7,88:0.5,77:0.4,AQs,AJs:0.8,ATs:0.4,KQs,KJs:0.5,QJs:0.5,JTs:0.5,AKo:0.2,AQo:0.4"),
        (CO to UTG) to ChartSpec("QQ+,AKs,AKo:0.8,JJ:0.2", "A5s:0.8,A4s:0.6,KTs:0.3,Q9s:0.2,65s:0.2",
            "JJ:0.8,TT,99,88:0.7,77:0.5,66:0.3,AQs,AJs,ATs:0.5,KQs,KJs:0.7,QJs:0.6,JTs:0.6,T9s:0.3,AKo:0.2,AQo:0.5"),
        (CO to HJ) to ChartSpec("QQ+,AK,JJ:0.4,AQs:0.3", "A5s,A4s:0.7,A3s:0.3,KTs:0.4,K9s:0.2,QTs:0.3,J9s:0.2,65s:0.3,AJo:0.2",
            "JJ:0.6,TT,99,88,77:0.6,66:0.4,AQs:0.7,AJs,ATs:0.7,KQs,KJs:0.7,QJs:0.7,JTs:0.7,T9s:0.4,AQo:0.5,KQo:0.2"),
        (BTN to UTG) to ChartSpec("QQ+,AKs,AKo:0.8,JJ:0.2", "A5s,A4s:0.6,K9s:0.3,Q9s:0.2,J9s:0.2,65s:0.3,54s:0.2",
            "JJ:0.8,TT-22,AQs-ATs,A9s:0.3,KQs,KJs,KTs:0.6,QJs,QTs:0.5,JTs,T9s,98s:0.6,87s:0.5,76s:0.4,AKo:0.2,AQo:0.7,AJo:0.2,KQo:0.4"),
        (BTN to HJ) to ChartSpec("QQ+,AK,JJ:0.4,AQs:0.3", "A5s,A4s,A3s:0.4,K9s:0.4,Q9s:0.3,J9s:0.3,T8s:0.2,65s:0.3,54s:0.3,AJo:0.2,KJo:0.2",
            "JJ:0.6,TT-22,AQs:0.7,AJs,ATs,A9s:0.5,A8s:0.3,KQs,KJs,KTs:0.7,QJs,QTs:0.6,JTs,T9s,98s:0.7,87s:0.6,76s:0.5,AQo,AJo:0.4,KQo:0.6"),
        (BTN to CO) to ChartSpec("JJ+,AK,AQs,TT:0.3,AQo:0.3",
            "A5s-A2s,K9s:0.5,K8s:0.3,Q9s:0.4,J9s:0.4,T8s:0.3,97s:0.2,65s:0.4,54s:0.4,AJo:0.3,KJo:0.3,QJo:0.2",
            "TT:0.7,99-22,AJs,ATs,A9s:0.7,A8s:0.5,A7s:0.3,KQs,KJs,KTs,QJs,QTs,JTs,T9s,98s,87s,76s:0.6,AQo:0.7,AJo:0.6,ATo:0.3,KQo:0.7,KJo:0.3"),
        (SB to UTG) to ChartSpec("QQ+,AKs,AKo:0.9,JJ:0.3", "A5s,A4s:0.5,KJs:0.3,KTs:0.2", "JJ:0.3,TT:0.3,AQs:0.3"),
        (SB to HJ) to ChartSpec("JJ+,AK,AQs:0.6,TT:0.3", "A5s,A4s,A3s:0.3,KTs:0.4,QJs:0.3,K9s:0.2,AQo:0.3",
            "TT:0.3,99:0.3,AJs:0.2,KQs:0.3"),
        (SB to CO) to ChartSpec("TT+,AK,AQ,AJs,KQs:0.5,99:0.4",
            "A5s-A2s,ATs:0.6,A9s:0.4,KJs,KTs,K9s:0.5,QJs:0.6,QTs:0.5,JTs:0.5,J9s:0.3,T9s:0.3,AJo:0.5,KQo:0.5",
            "99:0.3,88:0.4,77:0.3,KQs:0.3,QJs:0.2,JTs:0.3"),
        (SB to BTN) to ChartSpec("99+,AK,AQ,AJs,ATs:0.6,KQs,AJo:0.4,88:0.4",
            "A9s-A2s,KJs,KTs,K9s,K8s:0.5,QJs,QTs,Q9s:0.6,JTs,J9s:0.6,T9s,T8s:0.4,98s:0.5,87s:0.4,76s:0.3,65s:0.3,ATo:0.6,A9o:0.3,KQo,KJo:0.6,QJo:0.4",
            "88:0.3,77:0.5,66:0.4,55:0.3"),
        (BB to UTG) to ChartSpec("QQ+,AKs,AKo:0.7", "A5s:0.6,A4s:0.4,K9s:0.2,76s:0.2,65s:0.2",
            "JJ-22,AKo:0.3,AQs-A2s,AQo,AJo,ATo:0.3,KQs-K9s,KQo,KJo:0.4,QJs-Q9s,JTs,J9s,T9s,T8s,98s,97s:0.5,87s,86s:0.4,76s,65s,54s"),
        (BB to HJ) to ChartSpec("QQ+,AK,JJ:0.3,AQs:0.3", "A5s,A4s:0.6,A3s:0.3,K8s:0.3,Q9s:0.2,J9s:0.2,T8s:0.2,65s:0.3,54s:0.3",
            "JJ:0.7,TT-22,AQs:0.7,AJs-A2s,AQo,AJo,ATo:0.6,A9o:0.2,KQs-K7s,KQo,KJo,KTo:0.4,QJs-Q8s,QJo,QTo:0.3,JTs-J8s,JTo:0.4,T9s,T8s,T7s:0.3,98s,97s,87s,86s,76s,75s,65s,64s:0.4,54s,43s:0.3"),
        (BB to CO) to ChartSpec("JJ+,AK,AQs,TT:0.4,AQo:0.3",
            "A5s-A2s,K9s:0.4,K7s:0.3,Q8s:0.3,J8s:0.3,T7s:0.3,97s:0.3,86s:0.3,65s:0.3,54s:0.4,A9o:0.2,KTo:0.2",
            "TT:0.6,99-22,AJs-A6s,AQo:0.7,AJo,ATo,A9o:0.6,A8o:0.3,KQs-K2s,KQo,KJo,KTo,K9o:0.4,QJs-Q5s,QJo,QTo,Q9o:0.4,JTs-J6s,JTo,J9o:0.5,T9s-T6s,T9o,T8o:0.4,98s,97s,96s,87s,86s,85s:0.5,76s,75s,74s:0.3,65s,64s,54s,53s,43s,98o:0.4,87o:0.3"),
        (BB to BTN) to ChartSpec("TT+,AK,AQ,AJs,KQs:0.5,99:0.3,ATs:0.3",
            "A9s-A2s,K8s-K5s:0.5,Q8s:0.5,J8s:0.4,T7s:0.4,96s:0.4,85s:0.3,75s:0.4,64s:0.4,53s:0.4,A8o:0.3,A5o:0.3,K9o:0.3,Q9o:0.2,J9o:0.2",
            "99:0.7,88-22,AJs:0.5,ATs:0.7,A9s:0.5,A8s-A6s:0.6,AJo,ATo,A9o,A8o:0.7,A7o,A6o:0.6,A5o:0.7,A4o:0.7,A3o:0.5,A2o:0.4,KQs:0.5,KJs,KTs,K9s,K4s-K2s,KQo,KJo,KTo,K9o:0.7,K8o,K7o:0.6,K6o:0.3,QJs-Q9s,Q7s-Q2s,QJo,QTo,Q9o:0.8,Q8o:0.5,JTs,J9s,J7s-J4s,JTo,J9o:0.8,J8o:0.6,T9s,T8s,T6s,T5s:0.5,T9o,T8o:0.7,98s,97s,95s:0.5,98o,97o:0.4,87s,86s,84s:0.4,87o:0.6,76s,74s:0.5,76o:0.4,65s,63s:0.5,65o:0.3,54s,52s:0.3,43s,42s:0.3"),
        (BB to SB) to ChartSpec("99+,AK,AQ,AJs,ATs:0.5,KQs,88:0.3",
            "A9s-A2s:0.5,K9s-K6s:0.4,Q9s-Q7s:0.4,J8s:0.4,T7s:0.4,97s:0.4,86s:0.4,75s:0.4,64s:0.4,ATo:0.3,KJo:0.4,KTo:0.3,QJo:0.3",
            "88:0.7,77-22,AJs:0.5,ATs:0.5,A9s-A2s:0.5,AJo,ATo:0.7,A9o-A2o,KJs,KTs,K9s-K2s:0.6,KJo:0.6,KTo:0.7,K9o-K5o,QJs,QTs,Q9s-Q2s:0.6,QJo:0.7,QTo-Q7o,JTs,J9s,J8s-J2s:0.6,JTo-J7o,T9s,T8s,T7s-T4s:0.6,T9o-T7o,98s,97s-95s:0.6,98o,97o,87s,86s-85s:0.6,87o,86o:0.5,76s,75s:0.6,74s,76o,65s,64s:0.6,63s,65o:0.6,54s,53s,43s"),
    )

    enum class ThreeBetTemplate { EP_VS_IP, EP_VS_OOP, LP_VS_IP, LP_VS_OOP, SB_VS_BB }

    val VS_3BET: Map<ThreeBetTemplate, ChartSpec> = mapOf(
        ThreeBetTemplate.EP_VS_IP to ChartSpec("KK+,AKs,AKo:0.5,QQ:0.3", "A5s:0.5,A4s:0.3",
            "QQ:0.7,JJ-88,AQs,AJs,ATs:0.5,KQs,KJs:0.5,QJs:0.5,JTs:0.5,AKo:0.5,T9s:0.3"),
        ThreeBetTemplate.EP_VS_OOP to ChartSpec("KK+,AKs:0.6", "A5s:0.4,A4s:0.3,AKo:0.3",
            "QQ-66,AKo:0.7,AKs:0.4,AQs-ATs,AQo,KQs,KJs,QJs,JTs,T9s,98s:0.5"),
        ThreeBetTemplate.LP_VS_IP to ChartSpec("QQ+,AKs,AKo,JJ:0.3", "A5s,A4s,A3s:0.5,K9s:0.3,Q9s:0.3",
            "JJ:0.7,TT-55,AQs-A9s,AQo,AJo:0.3,KQs-KTs,QJs,QTs,JTs,T9s,98s,87s:0.5,KQo:0.5"),
        ThreeBetTemplate.LP_VS_OOP to ChartSpec("QQ+,AK,JJ:0.4,AQs:0.3", "A5s,A4s,A3s,A2s:0.5,K8s:0.3,Q9s:0.3,KJo:0.3",
            "JJ:0.6,TT-22,AQs:0.7,AJs-A6s,AQo,AJo,ATo:0.5,KQs-K9s,KQo,KJo:0.5,QJs-Q9s,QJo:0.5,JTs,J9s,T9s,T8s,98s,97s:0.5,87s,76s,65s,54s:0.5"),
        ThreeBetTemplate.SB_VS_BB to ChartSpec("QQ+,AK,JJ:0.5,AQs:0.4", "A5s-A2s,K9s:0.4,KTo:0.3,Q9s:0.3",
            "JJ:0.5,TT-44,AQs:0.6,AJs-A8s,AQo,AJo:0.5,KQs-KTs,KQo:0.5,QJs,QTs,JTs,T9s,98s:0.5"),
    )

    enum class FourBetTemplate { VS_EP, VS_LP, OOP }

    val VS_4BET: Map<FourBetTemplate, ChartSpec> = mapOf(
        FourBetTemplate.VS_EP to ChartSpec("KK+,AKs,AKo:0.5", "", "QQ,JJ:0.5,AKo:0.5,AQs:0.3"),
        FourBetTemplate.VS_LP to ChartSpec("QQ+,AK", "A5s:0.3", "JJ,TT:0.5,AQs,AJs:0.3,KQs:0.3"),
        FourBetTemplate.OOP to ChartSpec("QQ+,AKs,AKo:0.8", "A5s:0.3,A4s:0.2", "JJ,TT:0.5,AQs,AKo:0.2,KQs:0.3"),
    )

    enum class SqueezeTemplate { IP, SB, BB }

    val SQUEEZE: Map<SqueezeTemplate, ChartSpec> = mapOf(
        SqueezeTemplate.IP to ChartSpec("QQ+,AK,JJ:0.5", "A5s,A4s:0.5,KJs:0.3,KTs:0.3,QJs:0.3,T9s:0.3",
            "JJ:0.5,TT-55,AQs-ATs,KQs,QJs:0.7,JTs,T9s:0.7,98s,87s,76s,65s:0.5"),
        SqueezeTemplate.SB to ChartSpec("QQ+,AK,JJ:0.6,AQs:0.5", "A5s-A3s,KTs:0.5,K9s:0.4,QTs:0.3,AJo:0.3", ""),
        SqueezeTemplate.BB to ChartSpec("QQ+,AK,JJ:0.7,AQs:0.6", "A5s-A2s,K9s:0.5,Q9s:0.4,J9s:0.3,AJo:0.3,KQo:0.3",
            "JJ:0.3,TT-22,AQs:0.4,AJs-A6s,AQo,AJo:0.5,KQs-K9s,KQo:0.5,QJs-Q9s,JTs,J9s,T9s,T8s,98s,97s,87s,86s,76s,75s,65s,54s"),
    )

    fun threeBetTemplate(opener: Position, threeBettor: Position): ThreeBetTemplate = when {
        opener == SB -> ThreeBetTemplate.SB_VS_BB
        opener == UTG || opener == HJ ->
            if (threeBettor.isBlind) ThreeBetTemplate.EP_VS_OOP else ThreeBetTemplate.EP_VS_IP
        else -> if (threeBettor.isBlind) ThreeBetTemplate.LP_VS_OOP else ThreeBetTemplate.LP_VS_IP
    }

    fun fourBetTemplate(threeBettor: Position, fourBettor: Position): FourBetTemplate = when {
        !threeBettor.isInPositionVs(fourBettor) -> FourBetTemplate.OOP
        fourBettor == UTG || fourBettor == HJ -> FourBetTemplate.VS_EP
        else -> FourBetTemplate.VS_LP
    }

    fun squeezeTemplate(hero: Position): SqueezeTemplate = when (hero) {
        SB -> SqueezeTemplate.SB
        BB -> SqueezeTemplate.BB
        else -> SqueezeTemplate.IP
    }
}
