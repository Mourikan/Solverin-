package com.solverin.engine

import com.solverin.engine.core.Position
import com.solverin.engine.core.Stake
import com.solverin.engine.flop.Combos
import com.solverin.engine.math.PipelineBufferPool
import com.solverin.engine.pipeline.*
import com.solverin.engine.preflop.IsoDecision
import com.solverin.engine.river.RiverAction
import com.solverin.engine.river.RiverCardCategory
import com.solverin.engine.river.RiverNodeLock
import com.solverin.engine.river.RiverNodeLocks
import com.solverin.engine.river.RiverPolicy
import com.solverin.engine.river.RiverVillainNode
import com.solverin.engine.turn.TurnAction
import com.solverin.engine.turn.TurnCardCategory
import com.solverin.engine.turn.TurnPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FullGameExtremeAuditTest {
    private val engine = Pipelines.engine
    private fun k(s: String) = Combos.parse(s)

    private fun print(r: HandResult, tag: String) {
        for (rep in r.reports) println("$tag ${rep.street} ${rep.line} pot=%.2f eff=%.2f mass=%s mix=%s ev=%s size=%s rake=%.3f %.1fms".format(
            rep.potBb, rep.effectiveStackBb, rep.rangeMass.entries.joinToString { "${it.key.name.take(3)}:%.1f".format(it.value) },
            rep.heroMix.entries.filter { it.value > 0.005f }.joinToString { "${it.key}:%.2f".format(it.value) },
            rep.heroEv.entries.joinToString { "${it.key}:%.2f".format(it.value) }, rep.heroBetFraction?.let { "%.2f".format(it) }, rep.rakeAccruedBb, rep.latencyNanos / 1e6))
        println("$tag status=${r.status} finalPot=%.2f sdEq=%s total=%.0fms".format(r.finalPotBb, r.heroShowdownEquity?.let { "%.3f".format(it) }, r.totalNanos / 1e6))
    }

    private fun play(s: HandScript, tag: String): HandResult = engine.play(s).also { PipelineInvariants.all(it, tag); print(it, tag) }

    // ---- A: NL2 isolation of two limpers ---------------------------------------------------------------------

    @Test fun handA_IsoRaiseVsLimpersNl2() {
        val r = play(HandScript(Stake.NL2, PreflopAction(PreflopLine.ISO_RAISE_CALL, Position.BTN, Position.CO, listOf(Position.HJ, Position.CO)),
            Seat.OPENER, "AhJs", "Jd8c4h", "2s", "2d", StreetScript(StreetKind.BET_CALL, Seat.OPENER), StreetScript(StreetKind.BET_CALL, Seat.OPENER)), "A")
        val pre = r.report(Street.PREFLOP)!!
        val iso = pre.iso!!
        assertEquals(5.5, iso.sizeBb, 1e-12, "3.5 + 1·2 limpers, IP")
        assertEquals(IsoDecision.ISO_RAISE, iso.decision(com.solverin.engine.core.HandClass.parse("AJo")))
        assertEquals(2 * 5.5 + 1.0 + 1.5, pre.potBb, 1e-9, "iso pot = 2·5.5 + dead limp + blinds")
        println("A iso frequency=%.3f pFoldAll=%.3f ev=%s".format(iso.isoFrequency, iso.pFoldAll, pre.heroEv))
        assertTrue(pre.heroEv.getValue("ISO_RAISE") > pre.heroEv.getValue("OVER_LIMP") && pre.heroEv.getValue("ISO_RAISE") > 0.0)
        // Value on every street vs the inelastic NL2 caller, with the NL2 rake cap never exceeded.
        assertTrue(r.report(Street.FLOP)!!.heroMix.getValue("BET") > 0.5f && r.report(Street.TURN)!!.heroMix.getValue("SECOND_BARREL") > 0.5f)
        val river = r.report(Street.RIVER)!!
        assertTrue(river.heroMix.getValue("TRIPLE_BARREL") > 0.5f, "river value bet")
        val cap = Stake.NL2.defaultRake.capBb
        assertEquals(minOf(river.potBb * Stake.NL2.defaultRake.rate, cap), river.rakeAccruedBb, 1e-9, "exact NL2 rake / cap")
        assertTrue(river.rakeAccruedBb <= cap)
    }

    // ---- B: NL10 3-way sandwich, override for A♣Q♣, 1bb min-bet --------------------------------------------------

    @Test fun handB_MultiwayOverrideMinBetNl10() {
        val r = play(HandScript(Stake.NL10, PreflopAction(PreflopLine.RFI_COLD_CALL_BB_CALL, Position.CO, Position.BTN), Seat.OPENER, "AcQc",
            "Kd8c4h", "2s", "7c", StreetScript(StreetKind.MULTIWAY_BET_CALL, Seat.OPENER), StreetScript(StreetKind.MULTIWAY_BET_CALL, Seat.THIRD, betFraction = 0.04)), "B")
        val f = r.report(Street.FLOP)!!
        val bet = f.heroEv.getValue("BET"); val check = f.heroEv.getValue("CHECK")
        println("B flop AcQc bet=%.2f check=%.2f Δ=%.2f mix=%s".format(bet, check, bet - check, f.heroMix))
        if (bet - check >= com.solverin.engine.flop.FlopPolicy().exploitOverrideBb) assertTrue(f.heroMix.getValue("BET") > 0.5f, "override: A♣Q♣ bets")
        val t = r.report(Street.TURN)!!.turn!!
        assertTrue(t.minBet)
        for (h in 0 until Combos.COUNT) if (t.heroRange[h] > 0f) {
            val c = t.terms(TurnAction.CALL, h)!!
            if (c.equity >= t.potOdds!! || c.ev > 0) assertEquals(0f, t.frequency(TurnAction.FOLD, h), 1e-6f)
        }
        assertEquals(0f, r.report(Street.TURN)!!.heroMix["FOLD"] ?: 0f, 1e-6f, "0% fold vs 1bb")
    }

    // ---- C: NL25 progressive brake of Q♥Q♦ -------------------------------------------------------------------

    @Test fun handC_SetBrakesProgressivelyNl25() {
        val base = HandScript(Stake.NL25, PreflopAction(PreflopLine.RFI_CALL, Position.HJ, Position.CO), Seat.RESPONDER, "QhQd",
            "Qs9s4c", "2s", "7s", StreetScript(StreetKind.BET_CALL, Seat.OPENER), StreetScript(StreetKind.BET_CALL, Seat.OPENER))
        val acts = play(base, "C acts")
        val faces = play(base.copy(riverDecision = RiverScript(villainBetFraction = 0.66)), "C faces")
        val flop = acts.report(Street.FLOP)!!; val turn = acts.report(Street.TURN)!!
        assertTrue(flop.heroMix.getValue("RAISE") > 0.5f, "flop: value / protection raise")
        val ta = turn.turn!!
        assertTrue(ta.impact.has(TurnCardCategory.FLUSH_COMPLETING))
        val m = turn.turnSession!!.math(turn.turnContext!!)
        val h = k("QhQd")
        assertTrue(m.braked(h, m.villainFirstAction.raise), "ThreeFlushTurnBrake active (villain made share %.3f)".format(m.villainMadeShare(m.villainFirstAction.raise)))
        assertEquals(0f, turn.heroMix["RAISE"] ?: 0f, 1e-6f, "turn: no more 100% raise")
        assertTrue((turn.heroMix["CALL"] ?: 0f) > 0.5f, "turn: pot control")
        val river = acts.report(Street.RIVER)!!
        val big = if ((river.heroBetFraction ?: 0.0) > RiverPolicy().thinBet) river.heroMix.getValue("BET") else 0f
        assertEquals(0f, big, 1e-6f); assertEquals(0f, faces.report(Street.RIVER)!!.heroMix["RAISE"] ?: 0f, 1e-6f)
        println("C aggression flop raise=%.2f turn raise=%.2f call=%.2f river big=%.2f raise=%.2f".format(
            flop.heroMix.getValue("RAISE"), turn.heroMix["RAISE"] ?: 0f, turn.heroMix["CALL"] ?: 0f, big, faces.report(Street.RIVER)!!.heroMix["RAISE"] ?: 0f))
    }

    // ---- D: NL50 flop raise war, pairing turn -------------------------------------------------------------------

    @Test fun handD_RaiseWarPairedTurnNl50() {
        val r = play(HandScript(Stake.NL50, PreflopAction(PreflopLine.RFI_CALL, Position.CO, Position.BTN), Seat.OPENER, "AhAc",
            "JhTh4c", "Js", "2c", StreetScript(StreetKind.RAISE_CALL, Seat.OPENER), StreetScript(StreetKind.CHECK_CHECK),
            RiverScript(villainBetFraction = 0.75)), "D")
        val t = r.report(Street.TURN)!!
        val ta = t.turn!!
        assertEquals(TurnCardCategory.BOARD_PAIRING, ta.impact.category)
        val m = t.turnSession!!.math(t.turnContext!!)
        val flopNuts = r.report(Street.FLOP)!!.flop!!.nutAdvantage
        println("D flop ΔNuts=%.3f turn ΔNuts=%.3f villain made-hand share=%.3f".format(flopNuts, ta.nutAdvantage, m.villainMadeShare(m.villain)))
        assertTrue(ta.nutAdvantage < 0.0 && ta.nutAdvantage < flopNuts, "J♠ hands the raiser's trips / boats the nut advantage")
        val aa = k("AhAc")
        ta.betSizeBb?.let { b ->
            val resp = m.villainModel.respondToBet(if (ta.spot.heroInPosition) m.villainFirstAction.fold else m.villain, ta.spot.potBb, b)
            val rr = m.reraiseResponse(aa, ta.spot.potBb, b, 0.0, 0.0, resp, 1)
            assertTrue(!(rr.best == TurnAction.RAISE && rr.jamEquity < 0.5), "no negative stack-off with AA on the paired board")
        }
        val river = r.report(Street.RIVER)!!.river!!
        assertEquals(0f, river.frequency(RiverAction.RAISE, aa), 1e-6f, "no river raise with a capped overpair")
    }

    // ---- E: NL100 nut-blocker river bluff unlocked -----------------------------------------------------------------

    @Test fun handE_NutBlockerBluffUnlockedNl100() {
        val base = HandScript(Stake.NL100, PreflopAction(PreflopLine.RFI_CALL, Position.UTG, Position.BB), Seat.OPENER, "AsQd",
            "KsTs6h", "2d", "3s", StreetScript(StreetKind.BET_CALL, Seat.OPENER), StreetScript(StreetKind.BET_CALL, Seat.OPENER))
        val aq = play(base, "E AsQd")
        val qj = play(base.copy(heroHand = "QhJh"), "E QhJh")
        val ra = aq.report(Street.RIVER)!!
        val m = ra.riverSession!!.math(ra.riverContext!!)
        val tA = ra.river!!.terms(RiverAction.TRIPLE_BARREL, k("AsQd"))!!
        println("E AsQd bet=%.2f check=%.2f bNut=%.2f mix=%s | QhJh mix=%s ev=%s".format(tA.ev, ra.heroEv.getValue("CHECK"), tA.bNut, ra.heroMix, qj.report(Street.RIVER)!!.heroMix, qj.report(Street.RIVER)!!.heroEv))
        assertTrue(tA.bNut < RiverPolicy().exploitBlocker && tA.ev - ra.heroEv.getValue("CHECK") >= RiverPolicy().exploitOverrideBb)
        assertTrue(ra.heroMix.getValue("TRIPLE_BARREL") > 0.99f, "PostflopMassiveExploitOverride: A♠Q♦ bluffs")
        val rq = qj.report(Street.RIVER)!!
        assertEquals(1f, rq.heroMix.getValue("CHECK"), 1e-6f, "Q♥J♥ gives up")
        assertTrue(m.bet(k("QhJh"), 0.7).ev < 0.0)
    }

    // ---- F: NL100 3-bet pot OOP nuts: induce vs maniac, bet vs station ---------------------------------------------

    @Test fun handF_InduceVsManiacValueVsStationNl100() {
        val maniac = HandLocks(river = RiverNodeLocks.NONE.with(RiverVillainNode.BET, RiverNodeLock(frequency = 0.9, valueShare = 0.3))
            .with(RiverVillainNode.VS_BET, RiverNodeLock(fold = 0.55)))
        val station = HandLocks(river = RiverNodeLocks.NONE.with(RiverVillainNode.BET, RiverNodeLock(frequency = 0.1, valueShare = 0.9))
            .with(RiverVillainNode.VS_BET, RiverNodeLock(fold = 0.05)).with(RiverVillainNode.RIVER_ELASTICITY, RiverNodeLock(frequency = 0.95)))
        val base = HandScript(Stake.NL100, PreflopAction(PreflopLine.THREE_BET_CALL, Position.BTN, Position.SB), Seat.RESPONDER, "AhAc",
            "Ad7c2s", "Kh", "8d", StreetScript(StreetKind.BET_CALL, Seat.RESPONDER), StreetScript(StreetKind.BET_CALL, Seat.RESPONDER))
        val m = play(base.copy(locks = maniac), "F maniac")
        val s = play(base.copy(locks = station), "F station")
        val rm = m.report(Street.RIVER)!!; val rs = s.report(Street.RIVER)!!
        assertEquals(RiverCardCategory.MISSED_DRAWS_BRICK, rm.river!!.impact.category)
        assertTrue(!rm.river!!.spot.heroInPosition && m.heroShowdownEquity!! > 0.95, "OOP with the nuts")
        println("F maniac mix=%s ev=%s | station mix=%s ev=%s".format(rm.heroMix, rm.heroEv, rs.heroMix, rs.heroEv))
        assertTrue(rm.heroMix.getValue("CHECK") > 0.5f && rm.heroEv.getValue("CHECK") > rm.heroEv.getValue(rm.river!!.betAction!!.name), "check to induce vs the maniac")
        val sb = rs.river!!.betAction!!.name
        assertTrue(rs.heroMix.getValue(sb) > 0.5f && rs.heroEv.getValue(sb) > rs.heroEv.getValue("CHECK"), "bet / jam vs the station")
    }

    // ---- G: NL200 3-bet pot, low SPR, dominated draws ---------------------------------------------------------------

    @Test fun handG_ThreeBetPotDominatedDrawNl200() {
        val r = play(HandScript(Stake.NL200, PreflopAction(PreflopLine.THREE_BET_CALL, Position.BTN, Position.SB), Seat.OPENER, "QsJs",
            "9s6s2d", "3c", "7h", StreetScript(StreetKind.BET_CALL, Seat.RESPONDER), StreetScript(StreetKind.BET_CALL, Seat.RESPONDER, betFraction = 0.75),
            RiverScript(villainBetFraction = 1.0)), "G")
        val t = r.report(Street.TURN)!!
        val m = t.turnSession!!.math(t.turnContext!!)
        val ta = t.turn!!
        val bet = ta.spot.facingBetBb
        val jam = m.raiseVsBet(k("QsJs"), ta.spot.potBb + bet, bet, m.villainFirstAction.raise)
        println("G turn jam=%.2f raiseFold=%.3f line=${ta.spot.line} | river mix=%s ev=%s".format(jam.ev, m.raiseFold, r.report(Street.RIVER)!!.heroMix, r.report(Street.RIVER)!!.heroEv))
        assertTrue(jam.ev < 0.0 && jam.ev < 30.0, "no phantom +30bb bluff jam")
        assertEquals(0f, t.heroMix["RAISE"] ?: 0f, 1e-6f)
        assertTrue(m.raiseFold <= TurnPolicy().committedFoldMax + 1e-9)
        assertEquals(1f, r.report(Street.RIVER)!!.heroMix.getValue("FOLD"), 1e-6f, "missed dominated draw folds to the river jam")
    }

    // ---- H: NL200 checked turn: block bet + bluff-catcher selection ----------------------------------------------

    @Test fun handH_BlockBetAndBluffCatcherNl200() {
        val acts = play(HandScript(Stake.NL200, PreflopAction(PreflopLine.RFI_CALL, Position.CO, Position.BB), Seat.RESPONDER, "Kh5h",
            "Kd8c3h", "2s", "7c", StreetScript(StreetKind.BET_CALL, Seat.OPENER), StreetScript(StreetKind.CHECK_CHECK)), "H acts")
        val ra = acts.report(Street.RIVER)!!.river!!
        val sizes = ra.sizeFrequency()
        println("H block sizes=$sizes")
        assertTrue((sizes[RiverPolicy().blockBet] ?: 0.0) + (sizes[RiverPolicy().thinBet] ?: 0.0) > 0.0, "endogenous OOP block bet")
        val m = acts.report(Street.RIVER)!!.riverSession!!.math(acts.report(Street.RIVER)!!.riverContext!!)
        for (h in 0 until Combos.COUNT) if (ra.heroRange[h] > 0f && ra.betSize(h) <= RiverPolicy().thinBet && ra.frequency(ra.betAction!!, h) > 0f) {
            val t = ra.terms(ra.betAction!!, h)!!
            assertTrue(m.eqTotal(h) in RiverPolicy().blockValueLow..RiverPolicy().blockValueHigh || (t.equity < 0.5f && t.bNut < 1.0), "${Combos.name(h)}: block bet = thin value or blocker bluff")
        }
        val faces = play(HandScript(Stake.NL200, PreflopAction(PreflopLine.RFI_CALL, Position.CO, Position.BB), Seat.RESPONDER, "Qc9d",
            "Kh9h4c", "2d", "7s", StreetScript(StreetKind.BET_CALL, Seat.OPENER), StreetScript(StreetKind.BET_CALL, Seat.OPENER),
            RiverScript(villainBetFraction = 0.75)), "H faces")
        val rep = faces.report(Street.RIVER)!!
        val mf = rep.riverSession!!.math(rep.riverContext!!)
        val bet = 0.75 * rep.potBb
        val part = mf.facingPartition(bet)
        fun bluffsLeft(h: Int): Double { var n = 0.0; for (x in part.betIdx) if (Combos.MASK[h] and Combos.MASK[x] == 0L) n += part.bluff[x]; return n }
        val clean = k("Qc9d"); val blocker = k("Qh9d")
        println("H catcher Qc9d bluffs=%.2f ev=%.2f | Qh9d bluffs=%.2f ev=%.2f".format(bluffsLeft(clean), mf.call(clean, bet).ev, bluffsLeft(blocker), mf.call(blocker, bet).ev))
        assertTrue(bluffsLeft(clean) > bluffsLeft(blocker) && mf.call(clean, bet).ev > mf.call(blocker, bet).ev, "catcher that does not block busted hearts")
    }

    // ---- buffer pool ---------------------------------------------------------------------------------------------

    @Test fun bufferPoolReusesArrays() {
        PipelineBufferPool.resetStats()
        engine.play(HandScript(Stake.NL50, PreflopAction(PreflopLine.RFI_CALL, Position.CO, Position.BB), Seat.OPENER, "AhJd",
            "Kc8d3h", "5s", "2d", StreetScript(StreetKind.BET_CALL, Seat.OPENER), StreetScript(StreetKind.BET_CALL, Seat.OPENER)))
        val st = PipelineBufferPool.stats()
        println("POOL allocated=${st.allocated} reused=${st.reused} retained=${PipelineBufferPool.retained()}")
        assertTrue(st.reused > 10 * st.allocated, "scratch buffers are recycled")
        assertTrue(PipelineBufferPool.retained() <= 64)
        val a = PipelineBufferPool.acquireDoubles(7); a[0] = 3.0; PipelineBufferPool.release(a)
        val b = PipelineBufferPool.acquireDoubles(7)
        assertTrue(a === b && b[0] == 0.0, "reused and zeroed")
    }
}
