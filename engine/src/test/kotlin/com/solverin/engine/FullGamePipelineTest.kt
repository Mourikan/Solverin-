package com.solverin.engine

import com.solverin.engine.core.Card
import com.solverin.engine.core.Position
import com.solverin.engine.core.Stake
import com.solverin.engine.flop.Combos
import com.solverin.engine.flop.FlopAction
import com.solverin.engine.flop.FlopAnalysis
import com.solverin.engine.model.NodeLock
import com.solverin.engine.model.NodeLocks
import com.solverin.engine.model.VillainNode
import com.solverin.engine.pipeline.*
import com.solverin.engine.river.RiverAction
import com.solverin.engine.river.RiverAnalysis
import com.solverin.engine.river.RiverNodeLock
import com.solverin.engine.river.RiverNodeLocks
import com.solverin.engine.river.RiverVillainNode
import com.solverin.engine.turn.FlopLine
import com.solverin.engine.turn.TurnAction
import com.solverin.engine.turn.TurnAnalysis
import com.solverin.engine.turn.TurnNodeLock
import com.solverin.engine.turn.TurnNodeLocks
import com.solverin.engine.turn.TurnSession
import com.solverin.engine.turn.TurnSpot
import com.solverin.engine.turn.TurnVillainNode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pipeline-wide invariants on every street of a played hand. */
object PipelineInvariants {
    private fun range(w: FloatArray, board: IntArray, tag: String) {
        val dead = Combos.mask(board)
        for (k in 0 until Combos.COUNT) {
            val x = w[k]
            assertTrue(x.isFinite() && x in 0f..1f, "$tag weight ${Combos.name(k)} = $x")
            if (!Combos.isLive(k, dead)) assertEquals(0f, x, "$tag board card in range ${Combos.name(k)}")
        }
        assertTrue(Combos.total(w) > 0.0, "$tag empty range")
    }

    fun all(r: HandResult, tag: String) {
        val cap = r.script.stake.defaultRake.capBb
        for (rep in r.reports) {
            val t = "$tag ${rep.street}"
            assertTrue(rep.potBb > 0 && rep.potBb.isFinite(), "$t pot")
            assertTrue(rep.stacks.values.all { it >= -1e-9 }, "$t stacks ≥ 0")
            assertEquals(rep.effectiveStackBb, rep.stacks.values.min().coerceAtLeast(0.0), 1e-9, "$t effective stack")
            assertTrue(rep.rakeAccruedBb in 0.0..cap + 1e-9, "$t rake cap")
            assertEquals(1.0, rep.heroMix.values.sumOf { it.toDouble() }, 1e-4, "$t hero mix sum")
            assertTrue(rep.heroEv.values.all { it.isFinite() }, "$t hero EV finite")
            rep.flop?.let { a ->
                FlopInvariants.all(a)
                range(a.spot.heroRange, a.spot.board, "$t hero"); range(a.spot.villainRange, a.spot.board, "$t villain")
                a.spot.extraRange?.let { range(it, a.spot.board, "$t extra") }
                for (act in a.actions) for (h in 0 until Combos.COUNT) a.terms(act, h)?.let { assertTrue(it.rake <= cap + 1e-9, "$t rake ≤ cap") }
            }
            rep.turn?.let { a ->
                TurnInvariants.all(a, t)
                range(a.spot.heroRange, a.spot.board, "$t hero"); range(a.spot.villainRange, a.spot.board, "$t villain")
                a.spot.extraRange?.let { range(it, a.spot.board, "$t extra") }
                for (act in a.actions) for (h in 0 until Combos.COUNT) a.terms(act, h)?.let { assertTrue(it.rake <= cap + 1e-9, "$t rake ≤ cap") }
            }
            rep.river?.let { a ->
                RiverInvariants.all(a, t)
                range(a.spot.heroRange, a.spot.board, "$t hero"); range(a.spot.villainRange, a.spot.board, "$t villain")
                a.spot.extraRange?.let { range(it, a.spot.board, "$t extra") }
                for (act in a.actions) for (h in 0 until Combos.COUNT) a.terms(act, h)?.let {
                    assertTrue(it.rake <= cap + 1e-9, "$t rake ≤ cap")
                    assertEquals(1f, it.realization); assertEquals(0.0, it.impliedRiver); assertEquals(0.0, it.rioPenalty)
                }
            }
        }
        // Street-to-street accounting: the pot never shrinks and stacks never grow.
        for (i in 1 until r.reports.size) {
            val a = r.reports[i - 1]; val b = r.reports[i]
            assertTrue(b.potBb >= a.potBb - 1e-9, "$tag pot monotone")
            for (s in b.stacks.keys) assertTrue(b.stacks.getValue(s) <= a.stacks.getValue(s) + 1e-9, "$tag stack monotone")
            assertEquals(r.script.stackBb * b.stacks.size - b.stacks.values.sum() + deadOf(r), b.potBb, 1e-6, "$tag pot = dead + Σ investment")
        }
        assertTrue(r.finalPotBb > 0 && r.finalStacks.values.all { it >= -1e-9 })
        r.heroShowdownEquity?.let { assertTrue(it in 0.0..1.0) }
    }

    fun deadOf(r: HandResult): Double {
        val rep = r.reports.first()
        return rep.potBb - (r.script.stackBb * rep.stacks.size - rep.stacks.values.sum())
    }
}

/** Shared engine (preflop cache) for the pipeline suites. */
object Pipelines {
    val engine = HandPipelineEngine()

    fun turnLocks(fold: Double, raise: Double, foldRaise: Double, betFreq: Double, valueShare: Double, elasticity: Double): TurnNodeLocks {
        var l = TurnNodeLocks.NONE
        for (n in listOf(TurnVillainNode.VS_SECOND_BARREL, TurnVillainNode.VS_DELAYED_CBET, TurnVillainNode.VS_PROBE, TurnVillainNode.VS_DONK, TurnVillainNode.VS_BET))
            l = l.with(n, TurnNodeLock(fold = fold, raise = raise))
        l = l.with(TurnVillainNode.VS_TURN_RAISE, TurnNodeLock(fold = foldRaise))
        for (n in listOf(TurnVillainNode.BARREL, TurnVillainNode.DELAYED_CBET, TurnVillainNode.PROBE, TurnVillainNode.STAB, TurnVillainNode.DONK))
            l = l.with(n, TurnNodeLock(frequency = betFreq, valueShare = valueShare))
        return l.with(TurnVillainNode.RIVER_ELASTICITY, TurnNodeLock(frequency = elasticity))
    }

    fun riverLocks(fold: Double, over: Double, foldRaise: Double, bluff: Double, elasticity: Double): RiverNodeLocks = RiverNodeLocks.NONE
        .with(RiverVillainNode.VS_BET, RiverNodeLock(fold = fold, raise = 0.03))
        .with(RiverVillainNode.VS_OVERBET, RiverNodeLock(fold = over))
        .with(RiverVillainNode.VS_RAISE, RiverNodeLock(fold = foldRaise))
        .with(RiverVillainNode.BET, RiverNodeLock(valueShare = 1 - bluff))
        .with(RiverVillainNode.RIVER_ELASTICITY, RiverNodeLock(frequency = elasticity))

    fun preflopLocks(villain: Position, call: Double, raise: Double): NodeLocks =
        NodeLocks.NONE.with(VillainNode.DEFEND_VS_OPEN, villain, NodeLock(raise = raise, call = call))

    val STATION = HandLocks(preflopLocks(Position.BB, 0.70, 0.04), FlopBoards.STATION,
        turnLocks(0.10, 0.03, 0.20, 0.30, 0.85, 0.95), riverLocks(0.08, 0.12, 0.20, 0.30, 0.95))
    val OVERFOLDER = HandLocks(preflopLocks(Position.BB, 0.12, 0.02), FlopBoards.OVERFOLDER,
        turnLocks(0.75, 0.03, 0.80, 0.35, 0.90, 0.35), riverLocks(0.72, 0.82, 0.85, 0.20, 0.35))
}

class FullGamePipelineTest {
    private val engine = Pipelines.engine
    private fun k(s: String) = Combos.parse(s)

    private fun bluffMass(a: FlopAnalysis): Double {
        var n = 0.0; var d = 0.0
        for (h in 0 until Combos.COUNT) { val w = a.heroRange[h]; if (w <= 0f) continue; d += w
            val act = a.actions.firstOrNull { it == FlopAction.BET || it == FlopAction.DONK } ?: continue
            val t = a.terms(act, h) ?: continue
            if (t.equity < 0.5f) n += w * a.frequency(act, h) }
        return if (d > 0) n / d else 0.0
    }
    private fun bluffMass(a: TurnAnalysis): Double {
        val act = a.betAction ?: return 0.0
        var n = 0.0; var d = 0.0
        for (h in 0 until Combos.COUNT) { val w = a.heroRange[h]; if (w <= 0f) continue; d += w
            val t = a.terms(act, h) ?: continue
            if (t.equity < 0.5f) n += w * a.frequency(act, h) }
        return if (d > 0) n / d else 0.0
    }
    private fun riverMass(a: RiverAnalysis, pred: (Float) -> Boolean): Double {
        val act = a.betAction ?: return 0.0
        var n = 0.0; var d = 0.0
        for (h in 0 until Combos.COUNT) { val w = a.heroRange[h]; if (w <= 0f) continue; d += w
            val t = a.terms(act, h) ?: continue
            if (pred(t.equity)) n += w * a.frequency(act, h) }
        return if (d > 0) n / d else 0.0
    }

    private fun print(r: HandResult, tag: String) {
        for (rep in r.reports) println("$tag ${rep.street} ${rep.line} pot=%.2f eff=%.2f spr=%.2f mass=%s best=%s mix=%s ev=%s size=%s mdf=%s %.1fms".format(
            rep.potBb, rep.effectiveStackBb, rep.spr, rep.rangeMass.entries.joinToString { "${it.key.name.take(3)}:%.1f".format(it.value) }, rep.heroBest,
            rep.heroMix.entries.filter { it.value > 0.005f }.joinToString { "${it.key}:%.2f".format(it.value) },
            rep.heroEv.entries.joinToString { "${it.key}:%.2f".format(it.value) }, rep.heroBetFraction?.let { "%.2f".format(it) }, rep.mdf?.let { "%.2f".format(it) }, rep.latencyNanos / 1e6))
        println("$tag status=${r.status} finalPot=%.2f sdEq=%s total=%.0fms".format(r.finalPotBb, r.heroShowdownEquity?.let { "%.3f".format(it) }, r.totalNanos / 1e6))
    }

    // ---- Hand 1 -----------------------------------------------------------------------------------------------

    @Test fun hand1TripleBarrelBlockers() {
        val base = HandScript(Stake.NL100, PreflopAction(PreflopLine.RFI_CALL, Position.UTG, Position.BB), Seat.OPENER, "AsKh",
            "KsTs6h", "2d", "3s", StreetScript(StreetKind.BET_CALL, Seat.OPENER), StreetScript(StreetKind.BET_CALL, Seat.OPENER))
        val ak = engine.play(base)
        PipelineInvariants.all(ak, "H1 AsKh"); print(ak, "H1 AsKh")
        assertEquals(HandStatus.SHOWDOWN, ak.status)
        val river = ak.report(Street.RIVER)!!
        val ra = river.river!!
        assertEquals(RiverAction.TRIPLE_BARREL, ra.betAction)
        assertTrue(river.heroMix.getValue("TRIPLE_BARREL") > 0.99f, "A♠K♥ (nut flush) bets")
        val m = river.riverSession!!.math(river.riverContext!!)
        val h = k("AsKh")
        val best = ra.sizeCandidates.maxBy { m.bet(h, it).ev }
        assertEquals(best, river.heroBetFraction!!, 1e-9, "max-EV value size")
        assertTrue(m.bet(h, best).ev > ra.ev(RiverAction.CHECK, h))
        // Variant B: blocker selection (same river node, A♠Q♦ vs Q♥J♥).
        val aq = k("AsQd"); val qj = k("QhJh")
        val bAq = m.bet(aq, 0.7); val bQj = m.bet(qj, 0.7)
        println("H1 river AsQd bluff ev=%.2f bn=%.2f bf=%.2f | QhJh ev=%.2f bn=%.2f bf=%.2f".format(bAq.ev, bAq.bNut, bAq.bFold, bQj.ev, bQj.bNut, bQj.bFold))
        assertTrue(bAq.bNut < bQj.bNut && bAq.ev > bQj.ev, "A♠Q♦ out-bluffs Q♥J♥")
        val aqHand = engine.play(base.copy(heroHand = "AsQd"))
        PipelineInvariants.all(aqHand, "H1 AsQd"); print(aqHand, "H1 AsQd")
        assertTrue(aqHand.reports.map { it.street } == listOf(Street.PREFLOP, Street.FLOP, Street.TURN, Street.RIVER))
        // Range bookkeeping: flop → turn → river masses shrink by the Bayesian filters.
        val f = ak.report(Street.FLOP)!!; val t = ak.report(Street.TURN)!!
        assertTrue(t.rangeMass.getValue(Seat.RESPONDER) < f.rangeMass.getValue(Seat.RESPONDER))
        assertTrue(river.rangeMass.getValue(Seat.RESPONDER) < t.rangeMass.getValue(Seat.RESPONDER))
    }

    // ---- Hand 2 -----------------------------------------------------------------------------------------------

    @Test fun hand2CheckedTurnBluffCatcher() {
        val checked = HandScript(Stake.NL50, PreflopAction(PreflopLine.RFI_CALL, Position.CO, Position.BB), Seat.RESPONDER, "9s8s",
            "Kh9h4c", "2d", "7s", StreetScript(StreetKind.BET_CALL, Seat.OPENER), StreetScript(StreetKind.CHECK_CHECK),
            RiverScript(villainBetFraction = 0.75))
        val barrel = checked.copy(turnAction = StreetScript(StreetKind.BET_CALL, Seat.OPENER))
        for ((tag, script) in listOf("checked turn" to checked, "barrelled turn" to barrel)) {
            val r = engine.play(script)
            PipelineInvariants.all(r, "H2 $tag"); print(r, "H2 $tag")
            val rep = r.report(Street.RIVER)!!
            val a = rep.river!!
            val m = rep.riverSession!!.math(rep.riverContext!!)
            val bet = 0.75 * rep.potBb
            val part = m.facingPartition(bet)
            fun bluffsLeft(h: Int): Double { var n = 0.0; for (x in part.betIdx) if (Combos.MASK[h] and Combos.MASK[x] == 0L) n += part.bluff[x]; return n }
            var strict = 0
            for ((cx, bx) in listOf("Qc9d" to "Qh9d", "Qd9s" to "Qh9s", "Jc9d" to "Jh9d", "Tc9d" to "Th9d", "9s8s" to "9s8h")) {
                val x = k(cx); val y = k(bx)
                val ex = m.call(x, bet); val ey = m.call(y, bet)
                val bx2 = bluffsLeft(x); val by2 = bluffsLeft(y)
                println("H2 $tag $cx bluffs=%.2f share=%.3f callEv=%.2f | $bx bluffs=%.2f share=%.3f callEv=%.2f alpha=%.3f".format(
                    bx2, m.bluffShareFor(x, bet), ex.ev, by2, m.bluffShareFor(y, bet), ey.ev, a.alpha))
                assertEquals(m.showdown.category(x), m.showdown.category(y), "same made-hand category")
                // Anti-blocker rule: the catcher leaving more of villain's real bluffs never has the lower EV.
                if (bx2 > by2 + 1e-9) { assertTrue(ex.ev >= ey.ev - 1e-9, "$cx keeps more bluffs → better catcher"); strict++ }
                if (by2 > bx2 + 1e-9) { assertTrue(ey.ev >= ex.ev - 1e-9, "$bx keeps more bluffs → better catcher"); strict++ }
            }
            assertTrue(strict > 0, "$tag: blockers change villain's bluff count")
            if (script === barrel) {
                val x = k("Qc9d"); val y = k("Qh9d")
                assertTrue(bluffsLeft(x) > bluffsLeft(y) && m.call(x, bet).ev > m.call(y, bet).ev, "barrelled turn: Q♥ blocks the busted hearts")
                assertTrue(m.bluffShareFor(x, bet) > m.bluffShareFor(y, bet))
            }
        }
    }

    // ---- Hand 3 -----------------------------------------------------------------------------------------------

    @Test fun hand3ThreeBetPotLowSpr() {
        val script = HandScript(Stake.NL200, PreflopAction(PreflopLine.THREE_BET_CALL, Position.BTN, Position.SB), Seat.OPENER, "QsJs",
            "9s6s2d", "3c", null, StreetScript(StreetKind.BET_CALL, Seat.RESPONDER), StreetScript(StreetKind.BET_CALL, Seat.RESPONDER, betFraction = 0.75))
        val r = engine.play(script)
        PipelineInvariants.all(r, "H3"); print(r, "H3")
        val pre = r.report(Street.PREFLOP)!!
        assertTrue(pre.potBb > 15.0, "3-bet pot")
        val t = r.report(Street.TURN)!!
        val ta = t.turn!!
        assertEquals(FlopLine.RAISE_CALL, ta.spot.line, "3-bet pot propagates as condensed RAISE_CALL")
        val bet = ta.spot.facingBetBb
        assertTrue((t.effectiveStackBb - bet) / (t.potBb + 2 * bet) <= 1.2, "SPR after the call ≤ 1.2")
        val m = t.turnSession!!.math(t.turnContext!!)
        println("H3 raiseFold=%.3f".format(m.raiseFold))
        assertTrue(m.raiseFold <= com.solverin.engine.turn.TurnPolicy().committedFoldMax + 1e-9, "endogenous raise resistance")
        for (x in listOf("QsJs", "AsKs")) {
            val h = k(x)
            val jam = m.raiseVsBet(h, t.potBb + bet, bet, m.villainFirstAction.raise)
            val call = m.call(h, t.potBb + bet, bet, m.villainFirstAction.raise)
            println("H3 $x jam=%.2f call=%.2f".format(jam.ev, call.ev))
            if (x == "QsJs") {
                assertTrue(jam.ev < 0.0 && jam.ev < maxOf(0.0, call.ev), "no phantom bluff-jam EV")
                assertEquals(0f, ta.frequency(TurnAction.RAISE, h), 1e-6f)
            }
            assertTrue(jam.ev < 30.0)
        }
    }

    // ---- Hand 4 -----------------------------------------------------------------------------------------------

    @Test fun hand4SetBrakesOnFourFlush() {
        val base = HandScript(Stake.NL25, PreflopAction(PreflopLine.RFI_CALL, Position.HJ, Position.CO), Seat.RESPONDER, "QhQd",
            "Qs9s4c", "2s", "7s", StreetScript(StreetKind.BET_CALL, Seat.OPENER), StreetScript(StreetKind.BET_CALL, Seat.OPENER))
        val acts = engine.play(base)
        val faces = engine.play(base.copy(riverDecision = RiverScript(villainBetFraction = 0.66)))
        for ((tag, r) in listOf("H4 acts" to acts, "H4 faces" to faces)) { PipelineInvariants.all(r, tag); print(r, tag) }
        val flopRaise = acts.report(Street.FLOP)!!.heroMix["RAISE"] ?: 0f
        val turnRaise = acts.report(Street.TURN)!!.heroMix["RAISE"] ?: 0f
        val river = acts.report(Street.RIVER)!!
        assertEquals(com.solverin.engine.river.RiverCardCategory.FOUR_TO_A_FLUSH_OR_STRAIGHT, river.river!!.impact.category)
        val riverBig = if ((river.heroBetFraction ?: 0.0) > com.solverin.engine.river.RiverPolicy().thinBet) river.heroMix.getValue("BET") else 0f
        val riverRaise = faces.report(Street.RIVER)!!.heroMix["RAISE"] ?: 0f
        println("H4 aggression flop raise=%.2f turn raise=%.2f river big bet=%.2f river raise=%.2f".format(flopRaise, turnRaise, riverBig, riverRaise))
        assertTrue(flopRaise > 0.5f, "top set raises the flop")
        assertEquals(0f, riverBig, 1e-6f, "no big river bet without a spade")
        assertEquals(0f, riverRaise, 1e-6f, "no river raise without a spade")
        assertTrue(flopRaise >= turnRaise && turnRaise >= riverRaise, "aggression brakes street by street")
    }

    // ---- Hand 5 -----------------------------------------------------------------------------------------------

    @Test fun hand5ThreeWaySandwichMinBet() {
        val script = HandScript(Stake.NL10, PreflopAction(PreflopLine.RFI_COLD_CALL_BB_CALL, Position.CO, Position.BTN), Seat.OPENER, "AcQc",
            "Kd8c4h", "2s", "7c", StreetScript(StreetKind.MULTIWAY_BET_CALL, Seat.OPENER), StreetScript(StreetKind.MULTIWAY_BET_CALL, Seat.THIRD, betFraction = 0.04))
        val r = engine.play(script)
        PipelineInvariants.all(r, "H5"); print(r, "H5")
        val t = r.report(Street.TURN)!!
        val ta = t.turn!!
        assertTrue(ta.spot.multiway && ta.minBet && ta.maxFold != null, "3-way min-bet node")
        var total = 0.0; var folded = 0.0
        for (h in 0 until Combos.COUNT) { val w = ta.heroRange[h]; if (w <= 0f) continue; total += w; folded += w * ta.frequency(TurnAction.FOLD, h) }
        assertTrue(folded / total <= 1 - ta.mdf!! + 1e-6, "Fold ≤ 1 − MDF")
        val hu = TurnSession(TurnSpot(ta.spot.board, ta.spot.heroRange, ta.spot.villainRange, ta.spot.heroInPosition, ta.spot.heroIsAggressor,
            FlopLine.BET_CALL, ta.spot.potBb, ta.spot.effectiveStackBb, ta.spot.facingBetBb)).analyze(t.turnContext!!)
        var reduced = 0
        for (h in 0 until Combos.COUNT) {
            if (ta.heroRange[h] <= 0f) continue
            val mw = ta.terms(TurnAction.CALL, h)!!; val h2 = hu.terms(TurnAction.CALL, h)!!
            assertTrue(mw.realization <= h2.realization + 1e-6f)
            if (mw.calledEv < h2.calledEv - 1e-9) reduced++
        }
        println("H5 raise mw=%.3f hu=%.3f fold mw=%.3f hu=%.3f reduced=$reduced".format(ta.frequency(TurnAction.RAISE), hu.frequency(TurnAction.RAISE), ta.frequency(TurnAction.FOLD), hu.frequency(TurnAction.FOLD)))
        assertTrue(reduced > 0, "3-way branch discounts (0.88 realization)")
        assertTrue(ta.frequency(TurnAction.RAISE) <= hu.frequency(TurnAction.RAISE) + 1e-6, "sandwich: no raise expansion")
        assertTrue(r.report(Street.RIVER)!!.river!!.spot.multiway)
    }

    // ---- Hand 6 -----------------------------------------------------------------------------------------------

    @Test fun hand6StationVsOverfolderEndToEnd() {
        val base = HandScript(Stake.NL2, PreflopAction(PreflopLine.RFI_CALL, Position.CO, Position.BB), Seat.OPENER, "AhJd",
            "Kc8d3h", "5s", "2d", StreetScript(StreetKind.BET_CALL, Seat.OPENER), StreetScript(StreetKind.BET_CALL, Seat.OPENER))
        val st = engine.play(base.copy(locks = Pipelines.STATION))
        val of = engine.play(base.copy(locks = Pipelines.OVERFOLDER))
        PipelineInvariants.all(st, "H6 station"); PipelineInvariants.all(of, "H6 overfolder")
        print(st, "H6 station"); print(of, "H6 overfolder")
        val preSt = st.report(Street.PREFLOP)!!.preflop!!.raiseFrequency(); val preOf = of.report(Street.PREFLOP)!!.preflop!!.raiseFrequency()
        val flopSt = bluffMass(st.report(Street.FLOP)!!.flop!!); val flopOf = bluffMass(of.report(Street.FLOP)!!.flop!!)
        val turnSt = bluffMass(st.report(Street.TURN)!!.turn!!); val turnOf = bluffMass(of.report(Street.TURN)!!.turn!!)
        val rSt = st.report(Street.RIVER)!!.river!!; val rOf = of.report(Street.RIVER)!!.river!!
        val riverBluffSt = riverMass(rSt) { it < 0.5f }; val riverBluffOf = riverMass(rOf) { it < 0.5f }
        val thinSt = riverMass(rSt) { it in 0.5f..0.7f }; val thinOf = riverMass(rOf) { it in 0.5f..0.7f }
        println("H6 open st=%.3f of=%.3f | bluffs flop st=%.3f of=%.3f turn st=%.3f of=%.3f river st=%.3f of=%.3f | thin river st=%.3f of=%.3f".format(
            preSt, preOf, flopSt, flopOf, turnSt, turnOf, riverBluffSt, riverBluffOf, thinSt, thinOf))
        assertTrue(preOf > preSt, "preflop: steals expand vs the overfolder")
        assertTrue(flopOf > flopSt && turnOf > turnSt && riverBluffOf > riverBluffSt, "bluffs expand vs the overfolder on every street")
        assertTrue(riverBluffSt < 1e-3, "no river bluffs vs the station")
        assertTrue(thinSt >= thinOf, "thin value expands vs the station")
    }

    // ---- 50 random hands -------------------------------------------------------------------------------------------

    @Test fun randomHandSweep() {
        val rnd = Random(20261117)
        val lines = listOf(
            PreflopAction(PreflopLine.RFI_CALL, Position.UTG, Position.BB), PreflopAction(PreflopLine.RFI_CALL, Position.CO, Position.BTN),
            PreflopAction(PreflopLine.RFI_CALL, Position.BTN, Position.BB), PreflopAction(PreflopLine.RFI_CALL, Position.HJ, Position.SB),
            PreflopAction(PreflopLine.THREE_BET_CALL, Position.CO, Position.BTN), PreflopAction(PreflopLine.THREE_BET_CALL, Position.BTN, Position.BB),
            PreflopAction(PreflopLine.RFI_COLD_CALL_BB_CALL, Position.CO, Position.BTN), PreflopAction(PreflopLine.RFI_COLD_CALL_BB_CALL, Position.UTG, Position.SB),
        )
        val stakes = Stake.entries
        var played = 0; var smoothed = 0; var offTree = 0
        val t0 = System.currentTimeMillis()
        repeat(50) { i ->
            val pf = lines[rnd.nextInt(lines.size)]
            val deck = (0 until 52).shuffled(rnd)
            val cards = deck.take(7).map { Card.toString(it) }
            val hero = if (pf.third != null) Seat.entries[rnd.nextInt(3)] else Seat.entries[rnd.nextInt(2)]
            val seats = if (pf.third != null) Seat.entries else listOf(Seat.OPENER, Seat.RESPONDER)
            fun street(): StreetScript = if (pf.third != null) StreetScript(StreetKind.MULTIWAY_BET_CALL, seats[rnd.nextInt(seats.size)])
            else when (rnd.nextInt(4)) {
                0 -> StreetScript(StreetKind.CHECK_CHECK)
                1 -> StreetScript(StreetKind.RAISE_CALL, seats[rnd.nextInt(2)])
                2 -> StreetScript(StreetKind.BET_CALL, seats[rnd.nextInt(2)], betFraction = listOf(0.05, 0.33, 0.75, 1.5)[rnd.nextInt(4)])
                else -> StreetScript(StreetKind.BET_CALL, seats[rnd.nextInt(2)])
            }
            val script = HandScript(stakes[rnd.nextInt(stakes.size)], pf, hero, cards[0] + cards[1], cards[2] + cards[3] + cards[4], cards[5], cards[6],
                street(), street(), RiverScript(listOf(null, 0.33, 0.75, 1.5)[rnd.nextInt(4)]), stackBb = listOf(40.0, 100.0, 200.0)[rnd.nextInt(3)])
            val r = engine.play(script)
            PipelineInvariants.all(r, "random#$i ${script.preflop.line} ${script.flop}${script.turn}${script.river}")
            played++
            smoothed += r.reports.count { it.smoothed.isNotEmpty() }
            offTree += r.reports.count { it.heroOffTree }
        }
        println("random sweep: $played hands, $smoothed smoothed streets, $offTree off-tree hero streets, ${System.currentTimeMillis() - t0} ms")
        assertEquals(50, played)
    }

    @Test fun bayesianFloorNeverEmpties() {
        val board = intArrayOf(Card.parse("Ks"), Card.parse("Ts"), Card.parse("6h"))
        val prior = Combos.fromClassWeights(FloatArray(com.solverin.engine.core.HandClass.COUNT) { 1f }, Combos.mask(board))
        val (out, sm) = BayesianFloor.apply(FloatArray(Combos.COUNT), prior, board)
        assertTrue(sm && Combos.total(out) > 0.0 && out.all { it.isFinite() && it in 0f..1f })
        val strength = com.solverin.engine.flop.BoardStrength(board)
        for (kk in 0 until Combos.COUNT) if (out[kk] > 0f) assertTrue(strength.inTop(kk, BayesianFloor.TAIL), "upper tail kept")
        val (same, sm2) = BayesianFloor.apply(prior, prior, board)
        assertTrue(!sm2 && same === prior)
        val (fallback, _) = BayesianFloor.apply(FloatArray(Combos.COUNT), FloatArray(Combos.COUNT), board)
        assertTrue(Combos.total(fallback) > 0.0)
    }
}
