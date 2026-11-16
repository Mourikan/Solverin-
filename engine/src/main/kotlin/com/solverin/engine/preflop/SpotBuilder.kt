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
        return when (key.scenario) {
            Scenario.RFI -> h != Position.BB
            Scenario.VS_RFI -> v != Position.BB && v.ordinal < h.ordinal
            Scenario.VS_3BET -> h != Position.BB && v.ordinal > h.ordinal
            Scenario.VS_4BET -> v != Position.BB && v.ordinal < h.ordinal
            Scenario.SQUEEZE -> {
                val c = key.caller ?: return false
                v != Position.BB && v.ordinal < c.ordinal && c.ordinal < h.ordinal
            }
        }
    }

    private fun behind(h: Position, vararg exclude: Position) =
        Position.entries.filter { it.ordinal > h.ordinal && it !in exclude }

    private fun squeezeRisk(h: Position, vararg exclude: Position): Double {
        var none = 1.0
        for (p in behind(h, *exclude)) none *= 1 - vm.profile.squeeze
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
                    Opponent(
                        position = p,
                        response = vm.defenseVsOpen(p, h),
                        invested = p.blindBb,
                        reraiseTo = sz.threeBet(h, p),
                        continuesVsJam = vm.threeBettorVs4Bet(p, h).continueRange,
                        heroInPosition = h.isInPositionVs(p),
                    )
                }
                SpotNodes(null, RaiseNode(blinds, h.blindBb, x, opps))
            }
            Scenario.VS_RFI -> {
                val o = sz.open(v)
                val pot = o + blinds - v.blindBb
                val y = sz.threeBet(v, h)
                val call = CallNode(pot, h.blindBb, o, listOf(vm.open(v)), h.isInPositionVs(v), squeezeRisk(h, v))
                val opp = Opponent(v, vm.openerVs3Bet(v, h), o, sz.fourBet(y),
                    vm.fourBettorVsJam(v, h).call, h.isInPositionVs(v))
                SpotNodes(call, RaiseNode(pot, h.blindBb, y, listOf(opp)))
            }
            Scenario.VS_3BET -> {
                val x = sz.open(h)
                val y = sz.threeBet(h, v)
                val pot = x + y + blinds - h.blindBb - v.blindBb
                val threeBetRange = vm.defenseVsOpen(v, h).raise
                val call = CallNode(pot, x, y, listOf(threeBetRange), h.isInPositionVs(v), 0.0)
                val opp = Opponent(v, vm.threeBettorVs4Bet(v, h), y, stack, null, h.isInPositionVs(v))
                SpotNodes(call, RaiseNode(pot, x, sz.fourBet(y), listOf(opp)))
            }
            Scenario.VS_4BET -> {
                val y = sz.threeBet(v, h)
                val z = sz.fourBet(y)
                val pot = y + z + blinds - h.blindBb - v.blindBb
                val fourBetRange = vm.openerVs3Bet(v, h).raise
                val call = CallNode(pot, y, z, listOf(fourBetRange), h.isInPositionVs(v), 0.0)
                val opp = Opponent(v, vm.fourBettorVsJam(v, h), z, stack, null, h.isInPositionVs(v))
                SpotNodes(call, RaiseNode(pot, y, stack, listOf(opp)))
            }
            Scenario.SQUEEZE -> {
                val c = key.caller!!
                val o = sz.open(v)
                val pot = 2 * o + blinds - v.blindBb - c.blindBb
                val callerRange = vm.defenseVsOpen(c, v).call
                val call = CallNode(pot, h.blindBb, o, listOf(vm.open(v), callerRange),
                    h.isInPositionVs(v) && h.isInPositionVs(c), squeezeRisk(h, v, c))
                val s = sz.squeeze(v, 1)
                val opps = listOf(
                    Opponent(v, vm.openerVsSqueeze(v), o, sz.fourBet(s), vm.fourBettorVsJam(v, h).call, h.isInPositionVs(v)),
                    Opponent(c, vm.callerVsSqueeze(c, v), o, sz.fourBet(s), null, h.isInPositionVs(c)),
                )
                SpotNodes(call, RaiseNode(pot, h.blindBb, s, opps))
            }
        }
    }
}
