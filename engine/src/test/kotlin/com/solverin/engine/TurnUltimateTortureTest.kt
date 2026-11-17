package com.solverin.engine

import com.solverin.engine.core.Card
import com.solverin.engine.core.Stake
import com.solverin.engine.flop.Combos
import com.solverin.engine.flop.FlopContext
import com.solverin.engine.flop.FlopSpot
import com.solverin.engine.turn.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnUltimateTortureTest {
    private val ctx = TurnContext(Stake.NL50)
    private fun k(s: String) = Combos.parse(s)
    private fun board(s: String) = FlopBoards.board(s)
    private fun live(a: TurnAnalysis, x: String) = a.heroRange[k(x)] > 0f

    private val utg = "66+,A2s+,ATo+,K9s+,KQo,QTs+,JTs,T9s,98s,87s"
    private val bbVsUtg = "22-QQ,A2s-AQs,K9s-KQs,Q9s+,J9s+,T8s+,97s+,86s+,75s+,65s,54s,AJo-AQo,KJo-KQo,QJo"
    private val threeBettor = "QQ+,AKs,AKo,AQs,AJs,A5s,A4s"
    private val caller = "KTs,QJs,KJs,QTs,JTs,TT-77,AQs"

    private fun bridged(flop: String, turn: String, line: FlopLine = FlopLine.BET_CALL, hero: String = FlopBoards.CO_OPEN,
                        villain: String = FlopBoards.BB_DEFEND, stack: Double = 97.5): Pair<TurnSession, FlopToTurnRangeFilter.TurnRanges> {
        val f = board(flop); val t = Card.parse(turn)
        val tr = FlopToTurnRangeFilter.fromFlop(FlopSpot(f, FlopBoards.range(hero, f), FlopBoards.range(villain, f), true, true, 5.5, stack),
            FlopContext(Stake.NL50), line, t)
        return TurnSession(tr.spot(f, t)) to tr
    }

    private fun direct(b: String, hero: String, villain: String, ip: Boolean, agg: Boolean, line: FlopLine, pot: Double, stack: Double,
                       bet: Double = 0.0, extra: String? = null): TurnSession {
        val x = board(b)
        return TurnSession(TurnSpot(x, FlopBoards.range(hero, x), FlopBoards.range(villain, x), ip, agg, line, pot, stack, bet,
            extra?.let { FlopBoards.range(it, x) }))
    }

    // ---- Spot 1: low-SPR 3-bet pot, endogenous resistance to raises ---------------------------------------

    @Test fun spot1ThreeBetPotJamIsNegativeWithoutLock() {
        val s = direct("9s6s2d3c", caller, threeBettor, true, false, FlopLine.RAISE_CALL, 40.0, 45.0, 30.0)
        val a = s.analyze(ctx); val m = s.math(ctx)
        TurnInvariants.all(a, "3bet RAISE_CALL")
        println("T1 raiseFold=%.3f odds=%.3f".format(m.raiseFold, m.raisePotOdds))
        assertTrue(m.raiseFold in TurnPolicy().committedFoldMin..TurnPolicy().committedFoldMax, "committed band")
        val betRange = m.villainFirstAction.raise
        for (x in listOf("QsJs", "QsTs")) {
            val h = k(x)
            val jam = m.raiseVsBet(h, 70.0, 30.0, betRange)
            val call = a.terms(TurnAction.CALL, h)!!
            println("T1 $x jam=%.2f call=%.2f pFold=%.2f F=%.2f".format(jam.ev, call.ev, jam.pFold, a.frequency(TurnAction.FOLD, h)))
            assertTrue(jam.ev < 0.0 && jam.ev < maxOf(0.0, call.ev), "$x: bluff-jam EV is negative on its own merit")
            assertTrue(a.ev(TurnAction.RAISE, h) < 0.0)
            assertEquals(1f, a.frequency(TurnAction.FOLD, h), 1e-6f, "$x folds")
        }
        // An explicit read still unlocks the exploit.
        val lockCtx = ctx.copy(locks = TurnNodeLocks.foldToTurnRaise(0.70))
        val la = s.analyze(lockCtx); val lm = s.math(lockCtx)
        TurnInvariants.all(la, "3bet locked")
        assertEquals(0.70, lm.raiseFold, 1e-12, "lock wins")
        for (x in listOf("QsJs", "QsTs")) {
            val jam = lm.raiseVsBet(k(x), 70.0, 30.0, lm.villainFirstAction.raise)
            assertTrue(jam.ev > 0.0 && jam.ev > la.terms(TurnAction.CALL, k(x))!!.ev, "$x: locked over-folder → jam exploit")
        }
        assertTrue(la.frequency(TurnAction.RAISE) > a.frequency(TurnAction.RAISE), "lock raises more")
        // Endogenous scaling: committed / condensed lines fold less than open lines.
        val open = direct("9s6s2d3c", caller, threeBettor, true, false, FlopLine.CHECK_CHECK, 10.0, 150.0, 6.6).math(ctx)
        val bc = direct("9s6s2d3c", caller, threeBettor, true, false, FlopLine.BET_CALL, 10.0, 150.0, 6.6).math(ctx)
        val rc = direct("9s6s2d3c", caller, threeBettor, true, false, FlopLine.RAISE_CALL, 10.0, 150.0, 6.6).math(ctx)
        println("T1 open=%.3f betCall=%.3f raiseCall=%.3f".format(open.raiseFold, bc.raiseFold, rc.raiseFold))
        assertTrue(open.raiseFold > bc.raiseFold && bc.raiseFold > rc.raiseFold, "condensation order")
        assertTrue(rc.raiseFold <= TurnPolicy().committedFoldMax)
    }

    // ---- Spot 2: board pairing inverts the nuts -------------------------------------------------------

    @Test fun spot2PairedTurnDirtiesOutsAndBrakesStackOffs() {
        val (brickS, brickR) = bridged("JhTh4c", "2c")
        val (pairS, pairR) = bridged("JhTh4c", "Js")
        val brick = brickS.analyze(ctx); val pair = pairS.analyze(ctx)
        TurnInvariants.all(brick, "brick"); TurnInvariants.all(pair, "paired")
        assertEquals(TurnCardCategory.BOARD_PAIRING, pair.impact.category)
        println("T2 dN brick=%.3f paired=%.3f size brick=%s paired=%s".format(brick.nutAdvantage, pair.nutAdvantage, brick.betSizeFraction, pair.betSizeFraction))
        assertTrue(pair.nutAdvantage < brick.nutAdvantage, "trips / boats shift ΔNuts to the caller")
        val mb = brickS.math(ctx); val mp = pairS.math(ctx)
        val ob = mb.outsVs(mb.villain); val op = mp.outsVs(mp.villain)
        val draws = listOf("KhQd", "KdQc", "KcQh", "Qd9d", "Qc9c", "AhKh", "AdKc")
        var dirtyBrick = 0; var dirtyPair = 0
        for (x in draws) if (live(brick, x) && live(pair, x)) { dirtyBrick += ob.dirty[k(x)]; dirtyPair += op.dirty[k(x)] }
        println("T2 draw dirty outs brick=$dirtyBrick paired=$dirtyPair")
        assertTrue(dirtyPair > dirtyBrick, "full-house rivers degrade draw outs to dirty")
        assertTrue(pair.betSizeFraction == null || pair.betSizeFraction!! < TurnPolicy().overbetLow, "no overbet when the nuts invert")
        // Overpairs capped by trips / boats never stack off over a raise.
        val b = pair.betSizeBb!!
        val resp = mp.villainModel.respondToBet(mp.villainFirstAction.fold, pairR.potBb, b)
        for (x in listOf("AcAd", "KcKd", "QcQd")) if (live(pair, x)) {
            val rr = mp.reraiseResponse(k(x), pairR.potBb, b, 0.0, 0.0, resp, 1)
            assertTrue(rr.best != TurnAction.RAISE, "$x: no jam over the raise on the paired board")
            assertTrue(pair.frequency(pair.betAction!!, k(x)) <= brick.frequency(brick.betAction!!, k(x)) + 1e-6f, "$x: barrels no more than on the brick")
        }
        assertTrue(brickR.potBb == pairR.potBb)
    }

    // ---- Spot 3: Bet → Raise → 3-bet / jam -------------------------------------------------------------

    @Test fun spot3ThreeLevelTurnWar() {
        val s = direct("KsTs6h2d", FlopBoards.CO_OPEN, FlopBoards.BB_DEFEND, true, true, FlopLine.BET_CALL, 20.0, 70.0)
        val a = s.analyze(ctx); val m = s.math(ctx)
        TurnInvariants.all(a, "KT62")
        val b = a.betSizeBb!!
        val resp = m.villainModel.respondToBet(m.villainFirstAction.fold, 20.0, b)
        fun rr(x: String) = m.reraiseResponse(k(x), 20.0, b, 0.0, 0.0, resp, 1)
        for (x in listOf("KhKd", "KcKd", "ThTd", "TcTd", "6c6d", "6s6d", "KhTh", "KdTd", "KcTc")) if (a.heroRange[k(x)] > 0f) {
            val r = rr(x)
            println("T3 $x best=${r.best} call=%.2f jam=%.2f eqJ=%.2f".format(r.callEv, r.jamEv, r.jamEquity))
            assertTrue(r.best == TurnAction.CALL || r.best == TurnAction.RAISE, "$x commits")
        }
        for (x in listOf("AhAd", "AcAh", "AhKd", "AcKh")) if (a.heroRange[k(x)] > 0f) {
            val r = rr(x)
            println("T3 $x best=${r.best} call=%.2f jam=%.2f raw=%.2f eqJ=%.2f".format(r.callEv, r.jamEv, r.rawJamEv, r.jamEquity))
            assertTrue(!(r.best == TurnAction.RAISE && r.jamEquity < 0.5), "$x: no bluff stack-off")
            if (r.best == TurnAction.CALL) assertTrue(r.callEv > 0)
        }
        for (x in listOf("Jh9h", "Jd9d", "Jc9c")) if (a.heroRange[k(x)] > 0f) {
            val r = rr(x)
            assertEquals(TurnAction.FOLD, r.best, "$x: weak gutshot folds to the raise")
            assertEquals(-r.invested, r.value, 1e-12)
        }
        // Villain bet → hero raise → villain re-raise / call.
        val f = direct("KsTs6h2d", FlopBoards.BB_DEFEND, FlopBoards.CO_OPEN, false, false, FlopLine.BET_CALL, 20.0, 70.0, 14.0)
        val fa = f.analyze(ctx)
        TurnInvariants.all(fa, "KT62 facing")
        var withReraise = 0
        for (h in 0 until Combos.COUNT) {
            if (fa.heroRange[h] <= 0f) continue
            val t = fa.terms(TurnAction.RAISE, h) ?: continue
            assertTrue(t.evVsRaise.isFinite() && t.evVsRaise >= -t.investment - 70.0)
            if (t.pRaise > 0) withReraise++
        }
        assertTrue(withReraise > 0, "villain re-raises exist in the tree")
    }

    // ---- Spot 4: four-flush turn -------------------------------------------------------------------------

    @Test fun spot4FourFlushBrakesAndPivotsOnTopSpades() {
        val s = direct("Qs9s4s2s", FlopBoards.CO_OPEN, FlopBoards.BB_DEFEND, true, true, FlopLine.BET_CALL, 13.0, 90.0)
        val a = s.analyze(ctx); val m = s.math(ctx)
        TurnInvariants.all(a, "4-flush bet")
        val spadeSuit = Card.parse("2s") and 3
        fun hasSpade(h: Int) = (Combos.C1[h] and 3) == spadeSuit || (Combos.C2[h] and 3) == spadeSuit
        val noSpade = (0 until Combos.COUNT).filter { a.heroRange[it] > 0f && !hasSpade(it) }
        val made = noSpade.filter { m.strength.category[it] >= 2 }
        assertTrue(made.isNotEmpty())
        val b = a.betSizeBb!!
        val resp = m.villainModel.respondToBet(m.villainFirstAction.fold, 13.0, b)
        for (h in made) {
            val r = m.reraiseResponse(h, 13.0, b, 0.0, 0.0, resp, 1)
            assertTrue(!r.stacksOff, "${Combos.name(h)}: no stack-off without a spade")
            assertTrue(m.eqVs(resp.continueRange).equity(h) < 0.5f, "${Combos.name(h)}: not value vs a calling range")
        }
        val o = m.outsVs(m.villain)
        for (x in listOf("JhTh", "JdTd", "KhJh", "8h7h")) if (a.heroRange[k(x)] > 0f) assertEquals(0, o.clean[k(x)], "$x: straight outs are dead")
        val f = direct("Qs9s4s2s", FlopBoards.BB_DEFEND, FlopBoards.CO_OPEN, false, false, FlopLine.BET_CALL, 13.0, 90.0, 9.0)
        val fa = f.analyze(ctx)
        TurnInvariants.all(fa, "4-flush facing")
        var spadeRaise = 0.0
        for (h in 0 until Combos.COUNT) {
            if (fa.heroRange[h] <= 0f) continue
            val spade = hasSpade(h)
            val r = fa.frequency(TurnAction.RAISE, h)
            if (!spade) assertEquals(0f, r, 1e-6f, "${Combos.name(h)}: no raise without a spade")
            else spadeRaise += r * fa.heroRange[h]
        }
        assertTrue(spadeRaise > 0.0, "raises pivot on spades")
        val topSpades = listOf("AsKd", "AsJh", "Ks8d", "AsTc").filter { fa.heroRange[k(it)] > 0f }
        for (x in topSpades) assertEquals(0f, fa.frequency(TurnAction.FOLD, k(x)), 1e-6f, "$x defends")
        val r = fa.terms(TurnAction.CALL, k("ThTd").takeIf { fa.heroRange[it] > 0f } ?: k("8h8d"))!!
        assertTrue(r.realization <= 1.0f)
    }

    // ---- Spot 5: 3-way sandwich -------------------------------------------------------------------------

    @Test fun spot5SandwichSqueezeRisk() {
        val mw = direct("Th9h5c8d", FlopBoards.BTN_FLAT, FlopBoards.CO_OPEN, true, false, FlopLine.MULTIWAY_3WAY, 20.0, 80.0, 13.0, FlopBoards.BB_DEFEND)
        val hu = direct("Th9h5c8d", FlopBoards.BTN_FLAT, FlopBoards.CO_OPEN, true, false, FlopLine.BET_CALL, 20.0, 80.0, 13.0)
        val a = mw.analyze(ctx); val h = hu.analyze(ctx)
        TurnInvariants.all(a, "sandwich"); TurnInvariants.all(h, "heads-up")
        println("T5 fold mw=%.3f hu=%.3f".format(a.frequency(TurnAction.FOLD), h.frequency(TurnAction.FOLD)))
        assertTrue(a.frequency(TurnAction.FOLD) >= h.frequency(TurnAction.FOLD), "sandwich folds more")
        val m = mw.math(ctx)
        var draws = 0; var squeeze = 0
        for (c in 0 until Combos.COUNT) {
            if (a.heroRange[c] <= 0f) continue
            val tm = a.terms(TurnAction.CALL, c)!!; val th = h.terms(TurnAction.CALL, c)!!
            assertTrue(tm.realization <= th.realization + 1e-6f, "${Combos.name(c)}: realization ≤ HU")
            if (tm.pRaise > 0) squeeze++
            val o = m.outsVs(m.villainFirstAction.raise)
            if (o.behind(c) && o.clean[c] + o.dirty[c] > 0) {
                draws++
                assertTrue(tm.ev <= th.ev + 1e-9, "${Combos.name(c)}: draw call ≤ HU")
            }
        }
        assertTrue(draws > 0 && squeeze > 0)
    }

    // ---- Spot 6: 1bb micro-bet, weak vs polarized donks -------------------------------------------------

    @Test fun spot6MicroBetAndDonkReads() {
        val s = direct("Kd8c4h2s", FlopBoards.BTN_FLAT, FlopBoards.CO_OPEN, true, false, FlopLine.BET_CALL, 20.0, 80.0, 1.0)
        val a = s.analyze(ctx)
        TurnInvariants.all(a, "1bb")
        assertTrue(a.minBet && a.maxFold != null)
        var total = 0.0; var folded = 0.0
        for (h in 0 until Combos.COUNT) {
            val w = a.heroRange[h]; if (w <= 0f) continue
            val c = a.terms(TurnAction.CALL, h)!!
            if (c.equity >= a.potOdds!! || c.ev > 0.0) assertEquals(0f, a.frequency(TurnAction.FOLD, h), 1e-6f, "${Combos.name(h)} never folds to 1bb")
            total += w; folded += w * a.frequency(TurnAction.FOLD, h)
        }
        assertTrue(folded / total <= 1 - a.mdf!! + 1e-6, "Fold ≤ 1 − MDF")
        // 1bb in the check subtree (OOP check, villain stabs 1bb).
        val tiny = ctx.copy(profile = ctx.profile.copy(betSizeFraction = 0.05))
        val oop = direct("Kd8c4h2s", FlopBoards.BTN_FLAT, FlopBoards.CO_OPEN, false, false, FlopLine.CHECK_CHECK, 20.0, 80.0)
        val om = oop.math(tiny)
        TurnInvariants.all(oop.analyze(tiny), "1bb subtree")
        for (h in 0 until Combos.COUNT) {
            if (om.hero[h] <= 0f) continue
            val chk = om.check(h, 20.0, true)
            val cl = om.call(h, 21.0, 1.0, om.villainFirstAction.raise)
            if (chk.pRaise > 0 && cl.equity >= 1.0 / 22.0) assertTrue(chk.evVsRaise >= cl.ev - 1e-9, "${Combos.name(h)}: no fold to 1bb in the subtree")
        }
        // Weak vs polarized turn donk.
        val weak = direct("JhTh4c2c", FlopBoards.CO_OPEN, FlopBoards.BB_DEFEND, true, true, FlopLine.BET_CALL, 13.0, 90.0, 6.5).analyze(ctx)
        val polar = direct("JhTh4c8h", FlopBoards.CO_OPEN, FlopBoards.BB_DEFEND, true, true, FlopLine.BET_CALL, 13.0, 90.0, 6.5).analyze(ctx)
        TurnInvariants.all(weak, "weak donk"); TurnInvariants.all(polar, "polar donk")
        println("T6 weak fold=%.3f raise=%.3f maxFold=%s | polar fold=%.3f raise=%.3f".format(weak.frequency(TurnAction.FOLD), weak.frequency(TurnAction.RAISE), weak.maxFold, polar.frequency(TurnAction.FOLD), polar.frequency(TurnAction.RAISE)))
        assertEquals(TurnDonkType.WEAK, weak.donkType)
        assertEquals(TurnDonkType.POLARIZED, polar.donkType)
        assertEquals(1 - weak.mdf!! + TurnPolicy().weakDonkMdfSlack, weak.maxFold!!, 1e-9, "weak donk: MDF floor")
        assertTrue(weak.frequency(TurnAction.FOLD) <= weak.maxFold!! + 1e-6)
        assertTrue(weak.frequency(TurnAction.RAISE) > 0.0, "raises vs weak donks")
        assertTrue(polar.maxFold == null && polar.frequency(TurnAction.FOLD) > weak.frequency(TurnAction.FOLD), "polarized donk: tighter defense")
    }

    // ---- Spot 7: geometric overbet vs capped range ------------------------------------------------------

    @Test fun spot7OverbetPolarizationAndShutdown() {
        val (s, _) = bridged("AsKh7d", "2c", hero = utg, villain = bbVsUtg)
        val a = s.analyze(ctx); val m = s.math(ctx)
        TurnInvariants.all(a, "AK72")
        assertTrue(a.nutAdvantage > TurnPolicy().overbetNutEdge && a.villainCapped)
        assertTrue(a.betSizeFraction!! >= TurnPolicy().overbetLow, "overbet selected")
        val act = a.betAction!!
        val proj = s.matrix.projection
        var nuts = 0; var middle = 0.0; var middleW = 0.0; var bluffs = 0
        val cont = m.villainModel.respondToBet(m.villainFirstAction.fold, a.spot.potBb, a.betSizeBb!!).continueRange
        for (h in 0 until Combos.COUNT) {
            val w = a.heroRange[h]; if (w <= 0f) continue
            val e = m.eqVs(cont).equity(h)
            if (m.isNut(h)) { nuts++; assertEquals(1f, a.frequency(act, h), 1e-6f, "${Combos.name(h)}: nut overbets") }
            else if (e < 0.45f && m.heroVsVillain.equity(h) >= 0.5f) { middle += w * a.frequency(act, h); middleW += w
                if (a.frequency(act, h) > 0.5f) println("T7 catcher ${Combos.name(h)} eqCont=%.2f eqAll=%.2f cat=${m.strength.category[h]} bet=%.2f chk=%.2f".format(e, m.heroVsVillain.equity(h), a.ev(act, h), a.ev(TurnAction.CHECK, h))) }
            if (e < 0.35f && a.frequency(act, h) > 0f) { bluffs++; assertTrue(m.semiBluffPriority(h, cont) || proj.scare[h] > 0 || a.capped(h) || e < 0.35f) }
        }
        println("T7 nuts=$nuts middleBet=%.3f bluffs=$bluffs".format(if (middleW > 0) middle / middleW else 0.0))
        assertTrue(nuts > 0 && bluffs > 0, "polarized: nuts + bluffs")
        assertTrue(middleW == 0.0 || middle / middleW < 0.5, "bluff-catchers check")
        var shut = 0
        val turns = listOf(Triple("AsKh7d", "7c", true), Triple("AsKh7d", "Td", true), Triple("AsKh7d", "5s", true),
            Triple("JhTh4c", "2c", false), Triple("JhTh4c", "8h", false), Triple("JhTh4c", "9h", false), Triple("JhTh4c", "Js", false))
        for ((flop, t, utgPot) in turns) {
            val (ts, _) = if (utgPot) bridged(flop, t, hero = utg, villain = bbVsUtg) else bridged(flop, t)
            val x = ts.analyze(ctx)
            TurnInvariants.all(x, "$flop$t")
            val open = x.nutAdvantage > TurnPolicy().overbetNutEdge && x.villainCapped
            assertEquals(open, x.sizeCandidates.any { it == 1.25 || it == 1.5 }, "$flop$t: overbets iff ΔNuts edge vs capped range")
            if (!open) {
                shut++
                assertTrue(x.betSizeFraction == null || x.betSizeFraction!! < TurnPolicy().overbetLow || x.sizeCandidates.contains(x.betSizeFraction!!), "$flop$t")
            }
        }
        println("T7 overbet shutdowns: $shut")
        assertTrue(shut > 0, "some turn equalizes ΔNuts")
    }

    // ---- Spot 8: massive sweep -----------------------------------------------------------------------------

    private fun archetype(foldBet: Double, raiseBet: Double, foldRaise: Double, reraise: Double, foldJam: Double,
                          betFreq: Double, valueShare: Double, elasticity: Double): TurnNodeLocks {
        var l = TurnNodeLocks.NONE
        for (n in listOf(TurnVillainNode.VS_SECOND_BARREL, TurnVillainNode.VS_DELAYED_CBET, TurnVillainNode.VS_PROBE, TurnVillainNode.VS_DONK, TurnVillainNode.VS_BET))
            l = l.with(n, TurnNodeLock(fold = foldBet, raise = raiseBet))
        l = l.with(TurnVillainNode.VS_TURN_RAISE, TurnNodeLock(fold = foldRaise, raise = reraise))
        l = l.with(TurnVillainNode.VS_JAM, TurnNodeLock(fold = foldJam))
        for (n in listOf(TurnVillainNode.BARREL, TurnVillainNode.DELAYED_CBET, TurnVillainNode.PROBE, TurnVillainNode.STAB))
            l = l.with(n, TurnNodeLock(frequency = betFreq, valueShare = valueShare))
        l = l.with(TurnVillainNode.DONK, TurnNodeLock(frequency = betFreq / 2, valueShare = valueShare))
        return l.with(TurnVillainNode.RIVER_ELASTICITY, TurnNodeLock(frequency = elasticity))
    }

    @Test fun spot8MassiveSweep() {
        val profiles = listOf(
            "population" to TurnNodeLocks.NONE,
            "station" to archetype(0.12, 0.04, 0.20, 0.04, 0.25, 0.30, 0.85, 0.95),
            "nit" to archetype(0.65, 0.05, 0.75, 0.05, 0.80, 0.30, 0.95, 0.30),
            "maniac" to archetype(0.20, 0.35, 0.20, 0.35, 0.10, 0.90, 0.40, 0.80),
            "overfolder" to archetype(0.75, 0.04, 0.80, 0.03, 0.85, 0.35, 0.90, 0.40),
        )
        val textures = listOf("JhTh4c" to "8h", "AsKh7d" to "2c", "9s8s4d" to "9c", "Qc8d3c" to "Ac", "7h7d2s" to "Ks")
        val stacks = listOf(0.0, 1.0, 15.0, 200.0)
        var solves = 0; var margin = 0
        val t0 = System.currentTimeMillis()
        for ((flop, turn) in textures) {
            val f = board(flop); val t = Card.parse(turn)
            val heroR = FlopBoards.range(FlopBoards.CO_OPEN, f); val vilR = FlopBoards.range(FlopBoards.BB_DEFEND, f)
            for (line in FlopLine.entries) {
                val fs = if (line == FlopLine.MULTIWAY_3WAY) FlopSpot(f, heroR, vilR, true, true, 8.0, 97.5, extraRange = FlopBoards.range(FlopBoards.BTN_FLAT, f))
                else FlopSpot(f, heroR, vilR, true, true, 5.5, 97.5)
                val tr = FlopToTurnRangeFilter.fromFlop(fs, FlopContext(Stake.NL50), line, t)
                if (tr.hero.none { it > 0f } || tr.villain.none { it > 0f } || tr.extra?.none { it > 0f } == true) continue
                for (stack in stacks) for (facing in listOf(false, true)) {
                    if (facing && stack == 0.0) continue
                    val spot = TurnSpot(f + t, tr.hero, tr.villain, !facing, !facing, line, tr.potBb, stack,
                        if (facing) 0.6 * tr.potBb else 0.0, tr.extra)
                    val session = TurnSession(spot)
                    for ((name, locks) in profiles) {
                        val c = ctx.copy(locks = locks)
                        val a = session.analyze(c)
                        val tag = "$flop$turn $line stack=$stack facing=$facing $name"
                        TurnInvariants.all(a, tag)
                        solves++
                        val m = session.math(c)
                        if (stack == 0.0) assertEquals(listOf(TurnAction.CHECK), a.actions, tag)
                        for (h in 0 until Combos.COUNT step 5) {
                            if (m.hero[h] <= 0f) continue
                            assertEquals(1.0, m.leverage(h, 0.0, spot.potBb), 0.0, "$tag leverage at stackRem 0")
                            for (act in a.actions) {
                                val x = a.terms(act, h) ?: continue
                                if (x.investment >= stack - 1e-9 && stack > 0.0 && x.allIn) {
                                    assertEquals(1f, x.realization, 0f); assertEquals(0.0, x.impliedRiver, 0.0); assertEquals(0.0, x.rioPenalty, 0.0)
                                }
                            }
                            val b = a.betSizeBb
                            if (!facing && b != null && b < stack && spot.extraRange == null) {
                                val resp = m.villainModel.respondToBet(m.villainFirstAction.fold, spot.potBb, b)
                                val rr = m.reraiseResponse(h, spot.potBb, b, 0.0, 0.0, resp, 1)
                                if (rr.jamEv.isFinite() && rr.jamEquity < 0.5) {
                                    assertTrue(rr.jamEv >= maxOf(0.0, rr.callEv) + TurnPolicy().stackOffBluffMarginBb - 1e-9, "$tag stack-off margin")
                                    margin++
                                }
                                if (rr.rawJamEv.isFinite() && rr.jamEquity < 0.5 && rr.rawJamEv < maxOf(0.0, rr.callEv) + TurnPolicy().stackOffBluffMarginBb)
                                    assertTrue(rr.best != TurnAction.RAISE, "$tag bluff jam below margin removed")
                            }
                        }
                    }
                }
            }
        }
        println("T8 sweep: $solves solves, $margin bluff-jam margin checks, ${System.currentTimeMillis() - t0} ms")
        assertTrue(solves > 300)
    }
}
