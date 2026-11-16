package com.solverin.engine.preflop

import com.solverin.engine.core.Position
import com.solverin.engine.model.VillainModel
import com.solverin.engine.range.Scenario
import com.solverin.engine.range.SpotKey

/** Builds call/raise nodes for each preflop scenario from sizing + villain model. */
class SpotBuilder(private val ctx: PreflopContext, private val vm: VillainModel) {
    private val sz = ctx.sizing
    private val stack = ctx.effectiveStackBb

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
                val call = CallNode(pot, h.blindBb, o, listOf(vm.open(v)), h.isInPositionVs(v), squeezeRisk(h, v), 1)
                val opp = Opponent(v, vm.openerVs3Bet(v, h), o, sz.fourBet(y),
                    vm.fourBettorVsJam(v, h), stack, h.isInPositionVs(v), childKey = SpotKey.of(Scenario.VS_4BET, h, v))
                SpotNodes(call, RaiseNode(pot, h.blindBb, y, listOf(opp), 2))
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
                val fourBetRange = vm.openerVs3Bet(v, h).raise
                val call = CallNode(pot, y, z, listOf(fourBetRange), h.isInPositionVs(v), 0.0, 3)
                val opp = Opponent(v, vm.fourBettorVsJam(v, h), z, stack, null, stack, h.isInPositionVs(v))
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
            Scenario.VS_5BET -> {
                val y = sz.threeBet(h, v)
                val z = sz.fourBet(y)
                val pot = z + stack + blinds - h.blindBb - v.blindBb
                val call = CallNode(pot, z, stack, listOf(vm.threeBettorVs4Bet(v, h).raise), h.isInPositionVs(v), 0.0, 4)
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
        val call = CallNode(pot, o, sq, listOf(vm.squeezeRange(squeezer, opener)), ip, 0.0, 2)
        val opp = Opponent(squeezer, vm.squeezerVs4Bet(squeezer, opener), sq, stack, null, stack, ip)
        return SpotNodes(call, RaiseNode(pot, o, sz.fourBet(sq), listOf(opp), 3))
    }
}
