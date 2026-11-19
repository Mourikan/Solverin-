package com.solverin.engine.preflop

import com.solverin.engine.core.HandClass
import com.solverin.engine.core.Position
import com.solverin.engine.eval.EquityTable
import com.solverin.engine.model.HandRanking
import com.solverin.engine.model.VillainNode
import com.solverin.engine.model.VillainModel
import com.solverin.engine.range.Scenario
import com.solverin.engine.range.SpotKey

/** Builds call/raise nodes for each preflop scenario from sizing + villain model. */
class SpotBuilder(private val ctx: PreflopContext, private val vm: VillainModel) {
    private val sz = ctx.sizing
    private val stack = ctx.effectiveStackBb
    private val discipline = ctx.policy.heroDiscipline

    /** A node lock that polarizes the villain's raise (valueShare < POLARIZED_LOCK) keeps his range as read. */
    private fun polarized(node: VillainNode, v: Position, versus: Position) =
        (ctx.locks.get(node, v, versus)?.valueShare ?: 1.0) < POLARIZED_LOCK

    /**
     * Endogenous collapse of a 4-bet / 5-bet range: only the strongest [top] share of all hands survives (5-bet: AA, KK,
     * QQ, AKs and part of AKo; 4-bet: QQ+, AK and the next value hands). Hero discipline only (the population solve keeps
     * the model's range).
     */
    private fun collapse(range: FloatArray, top: Double): FloatArray {
        if (!discipline) return range
        val out = FloatArray(range.size)
        var left = top * HandClass.TOTAL_COMBOS
        for (h in RANKING.byStrength) {
            if (left <= 0.0) break
            val c = HandClass.combos(h).toDouble()
            val share = minOf(1.0, left / c)
            out[h] = (range[h] * share).toFloat()
            left -= c
        }
        return if (out.any { it > 0f }) out else range
    }

    /**
     * Hero-side 4-bet range facing a 4-bet: the value core collapsed to [top] is never narrower than QQ+ / AK (a 4-bet
     * range always holds them, whatever the opener's fold-to-3-bet profile says), and the polarized bluff component of
     * the population range (hands below the merged region, e.g. A5s) is kept up to [FOUR_BET_BLUFF_TO_VALUE] × value:
     * collapsing to pure value would leave AA alone and fold KK / AK.
     */
    private fun collapseFourBet(range: FloatArray, top: Double): FloatArray {
        if (!discipline) return range
        val out = collapse(range, top)
        for (h in FOUR_BET_VALUE_CORE) out[h] = maxOf(out[h], 1f)
        var value = 0.0
        for (h in 0 until HandClass.COUNT) value += out[h].toDouble() * HandClass.combos(h)
        val bluffs = FloatArray(HandClass.COUNT)
        var bluffMass = 0.0
        var cum = 0.0
        for (h in RANKING.byStrength) {
            val c = HandClass.combos(h).toDouble()
            cum += c
            if (cum <= FOUR_BET_MERGED_TOP * HandClass.TOTAL_COMBOS || out[h] > 0f || range[h] <= 0f) continue
            bluffs[h] = range[h]; bluffMass += range[h].toDouble() * c
        }
        if (bluffMass > 0.0) {
            val scale = minOf(1.0, FOUR_BET_BLUFF_TO_VALUE * value / bluffMass).toFloat()
            for (h in 0 until HandClass.COUNT) if (bluffs[h] > 0f) out[h] = bluffs[h] * scale
        }
        return out
    }

    fun validate(key: SpotKey): Boolean {
        val h = key.hero; val v = key.villain
        val noCaller = key.caller == null
        return when (key.scenario) {
            Scenario.RFI -> noCaller && h != Position.BB && v == Position.BB
            Scenario.VS_RFI -> noCaller && v != Position.BB && v.ordinal < h.ordinal
            Scenario.VS_3BET -> noCaller && h != Position.BB && v.ordinal > h.ordinal
            Scenario.VS_4BET -> {
                val c = key.caller
                v != Position.BB && v.ordinal < h.ordinal && (c == null || (v.ordinal < c.ordinal && c.ordinal < h.ordinal))
            }
            Scenario.SQUEEZE -> {
                val c = key.caller ?: return false
                v != Position.BB && v.ordinal < c.ordinal && c.ordinal < h.ordinal
            }
            Scenario.VS_SQUEEZE_OPENER -> {
                val c = key.caller ?: return false
                h != Position.BB && h.ordinal < c.ordinal && c.ordinal < v.ordinal
            }
            Scenario.VS_SQUEEZE_CALLER -> {
                val o = key.caller ?: return false
                o != Position.BB && o.ordinal < h.ordinal && h.ordinal < v.ordinal
            }
            Scenario.VS_5BET -> noCaller && h != Position.BB && v.ordinal > h.ordinal
            Scenario.VS_3BET_COLD -> {
                val o = key.caller ?: return false
                o != Position.BB && o.ordinal < v.ordinal && v.ordinal < h.ordinal
            }
        }
    }

    private fun behind(h: Position, vararg exclude: Position) =
        Position.entries.filter { it.ordinal > h.ordinal && it !in exclude }

    private fun squeezeRisk(h: Position, opener: Position, vararg exclude: Position): Double {
        var none = 1.0
        for (p in behind(h, opener, *exclude)) none *= 1 - vm.squeezeFrequency(p, opener)
        return 1 - none
    }

    fun build(key: SpotKey): SpotNodes {
        require(validate(key)) { "Invalid spot $key" }
        val h = key.hero; val v = key.villain
        val blinds = Position.SB.blindBb + Position.BB.blindBb
        return when (key.scenario) {
            Scenario.RFI -> {
                val x = sz.open(h)
                val opps = behind(h).map { p ->
                    var noSq = 1.0; var noOver = 1.0
                    for (q in behind(p)) {
                        noSq *= 1 - vm.squeezeFrequency(q, h)
                        noOver *= 1 - vm.defenseFrequencies(q, h).second
                    }
                    Opponent(
                        position = p,
                        response = vm.defenseVsOpen(p, h),
                        invested = p.blindBb,
                        reraiseTo = sz.threeBet(h, p),
                        heroReRaise = vm.threeBettorVs4Bet(p, h),
                        heroReRaiseTo = sz.fourBet(sz.threeBet(h, p)),
                        heroInPosition = h.isInPositionVs(p),
                        childKey = SpotKey.of(Scenario.VS_3BET, h, p),
                        squeezeBehind = 1 - noSq,
                        overcallBehind = 1 - noOver,
                    )
                }
                SpotNodes(null, RaiseNode(blinds, h.blindBb, x, opps, 1))
            }
            Scenario.VS_RFI -> {
                val o = sz.open(v)
                val pot = o + blinds - v.blindBb
                val y = sz.threeBet(v, h)
                var noOver = 1.0
                for (q in behind(h, v)) noOver *= 1 - vm.defenseFrequencies(q, v).second
                val call = CallNode(pot, h.blindBb, o, listOf(vm.open(v)), h.isInPositionVs(v), squeezeRisk(h, v), 1,
                    overcallBehind = 1 - noOver)
                // Players behind act first on hero's 3bet (cold 4bet / cold call), then the opener.
                val cold = behind(h, v).map { q ->
                    Opponent(q, vm.coldVs3Bet(q, v), q.blindBb, sz.coldFourBet(y), null, stack, h.isInPositionVs(q))
                }
                val opp = Opponent(v, vm.openerVs3Bet(v, h), o, sz.fourBet(y),
                    vm.fourBettorVsJam(v, h), stack, h.isInPositionVs(v), childKey = SpotKey.of(Scenario.VS_4BET, h, v),
                    main = true)
                SpotNodes(call, RaiseNode(pot, h.blindBb, y, cold + opp, 2))
            }
            Scenario.VS_3BET -> {
                val x = sz.open(h)
                val y = sz.threeBet(h, v)
                val pot = x + y + blinds - h.blindBb - v.blindBb
                val threeBetRange = vm.defenseVsOpen(v, h).raise
                val call = CallNode(pot, x, y, listOf(threeBetRange), h.isInPositionVs(v), 0.0, 2)
                val opp = Opponent(v, vm.threeBettorVs4Bet(v, h), y, stack, null, stack, h.isInPositionVs(v),
                    childKey = SpotKey.of(Scenario.VS_5BET, h, v))
                SpotNodes(call, RaiseNode(pot, x, sz.fourBet(y), listOf(opp), 3))
            }
            Scenario.VS_4BET -> if (key.caller != null) {
                val c = key.caller!!
                val o = sz.open(v)
                val sq = sz.squeeze(v, 1)
                val z = sz.fourBet(sq)
                val pot = sq + z + o + blinds - h.blindBb - v.blindBb - c.blindBb
                val call = CallNode(pot, sq, z, listOf(vm.openerVsSqueeze(v).raise), h.isInPositionVs(v), 0.0, 3)
                val opp = Opponent(v, vm.fourBettorVsJamAfterSqueeze(v), z, stack, null, stack, h.isInPositionVs(v))
                SpotNodes(call, RaiseNode(pot, sq, stack, listOf(opp), 4))
            } else {
                val y = sz.threeBet(v, h)
                val z = sz.fourBet(y)
                val pot = y + z + blinds - h.blindBb - v.blindBb
                val raw = vm.openerVs3Bet(v, h).raise
                // CO / BB: the 4-bettor's range is condensed further (StakePolicy.fourBetCoBbRangeTop) unless a polarizing lock.
                val coBb = h == Position.CO || h == Position.BB
                val fourBetRange = if (polarized(VillainNode.VS_3BET, v, h)) raw
                else collapseFourBet(raw, if (coBb) minOf(FOUR_BET_TOP, ctx.policy.fourBetCoBbRangeTop) else FOUR_BET_TOP)
                val ip = h.isInPositionVs(v)
                val call = CallNode(pot, y, z, listOf(fourBetRange), ip, 0.0, 3,
                    realizationFactor = if (!ip && discipline) OOP_VS_FOUR_BET else 1.0)
                // The jam is answered by the same 4-bet range the flat is priced against.
                val jamResponse = if (fourBetRange === raw) vm.fourBettorVsJam(v, h) else vm.fourBettorVsJam(v, h, fourBetRange)
                val opp = Opponent(v, jamResponse, z, stack, null, stack, h.isInPositionVs(v))
                SpotNodes(call, RaiseNode(pot, y, stack, listOf(opp), 4))
            }
            Scenario.SQUEEZE -> {
                val c = key.caller!!
                val o = sz.open(v)
                val pot = 2 * o + blinds - v.blindBb - c.blindBb
                val callerRange = vm.defenseVsOpen(c, v).call
                val call = CallNode(pot, h.blindBb, o, listOf(vm.open(v), callerRange),
                    h.isInPositionVs(v) && h.isInPositionVs(c), squeezeRisk(h, v, c), 1)
                val s = sz.squeeze(v, 1)
                val opps = listOf(
                    Opponent(v, vm.openerVsSqueeze(v), o, sz.fourBet(s), vm.fourBettorVsJamAfterSqueeze(v), stack, h.isInPositionVs(v),
                        childKey = SpotKey.of(Scenario.VS_4BET, h, v, c)),
                    Opponent(c, vm.callerVsSqueeze(c, v), o, sz.fourBet(s), null, stack, h.isInPositionVs(c)),
                )
                SpotNodes(call, RaiseNode(pot, h.blindBb, s, opps, 2))
            }
            Scenario.VS_SQUEEZE_OPENER -> facingSqueeze(h, opener = h, caller = key.caller!!, squeezer = v)
            Scenario.VS_SQUEEZE_CALLER -> facingSqueeze(h, opener = key.caller!!, caller = h, squeezer = v)
            Scenario.VS_3BET_COLD -> {
                val opener = key.caller!!
                val o = sz.open(opener)
                val y = sz.threeBet(opener, v)
                val pot = o + y + blinds - opener.blindBb - v.blindBb
                val openerResp = vm.openerVs3Bet(opener, v)
                val call = CallNode(
                    pot, h.blindBb, y, listOf(vm.defenseVsOpen(v, opener).raise), h.isInPositionVs(v),
                    squeezeRisk = openerResp.raiseShare, potLevel = 2, overcallBehind = openerResp.callShare,
                )
                val z = sz.coldFourBet(y)
                val opps = listOf(
                    Opponent(opener, vm.openerVsCold4Bet(opener, v), o, stack, null, stack, h.isInPositionVs(opener)),
                    Opponent(v, vm.threeBettorVs4Bet(v, opener), y, stack, null, stack, h.isInPositionVs(v), main = true),
                )
                SpotNodes(call, RaiseNode(pot, h.blindBb, z, opps, 3))
            }
            Scenario.VS_5BET -> {
                val y = sz.threeBet(h, v)
                val z = sz.fourBet(y)
                val pot = z + stack + blinds - h.blindBb - v.blindBb
                val raw = vm.threeBettorVs4Bet(v, h).raise
                val fiveBetRange = if (polarized(VillainNode.VS_4BET, v, h)) raw else collapse(raw, FIVE_BET_TOP)
                val call = CallNode(pot, z, stack, listOf(fiveBetRange), h.isInPositionVs(v), 0.0, 4)
                SpotNodes(call, null)
            }
        }
    }

    /** Hero (opener or cold caller, the other one folded/still to act) facing a squeeze. */
    private fun facingSqueeze(h: Position, opener: Position, caller: Position, squeezer: Position): SpotNodes {
        val blinds = Position.SB.blindBb + Position.BB.blindBb
        val o = sz.open(opener)
        val sq = sz.squeeze(opener, 1)
        val pot = 2 * o + sq + blinds - opener.blindBb - caller.blindBb - squeezer.blindBb
        val ip = h.isInPositionVs(squeezer)
        val call = CallNode(pot, o, sq, listOf(vm.squeezeRange(squeezer, opener)), ip, 0.0, 2,
            realizationFactor = if (h == caller && discipline) SANDWICHED_CALLER else 1.0)
        val opp = Opponent(squeezer, vm.squeezerVs4Bet(squeezer, opener), sq, stack, null, stack, ip)
        return SpotNodes(call, RaiseNode(pot, o, sz.fourBet(sq), listOf(opp), 3))
    }

    private companion object {
        val RANKING = HandRanking(EquityTable.get())
        const val POLARIZED_LOCK = 0.65
        const val FIVE_BET_TOP = 0.022
        const val FOUR_BET_TOP = 0.045
        /** Hands above this strength share are merged value (removed from a collapsed 4-bet range, not bluffs). */
        const val FOUR_BET_MERGED_TOP = 0.10
        const val FOUR_BET_BLUFF_TO_VALUE = 0.45
        val FOUR_BET_VALUE_CORE = intArrayOf(HandClass.parse("AA"), HandClass.parse("KK"), HandClass.parse("QQ"), HandClass.parse("AKs"), HandClass.parse("AKo"))
        const val OOP_VS_FOUR_BET = 0.88
        const val SANDWICHED_CALLER = 0.90
    }
}
