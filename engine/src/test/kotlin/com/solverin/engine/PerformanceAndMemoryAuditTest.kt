package com.solverin.engine

import com.solverin.engine.core.Card
import com.solverin.engine.core.Position
import com.solverin.engine.core.Stake
import com.solverin.engine.pipeline.*
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class PerformanceAndMemoryAuditTest {
    private val engine = Pipelines.engine

    private fun usedHeap(): Long {
        val rt = Runtime.getRuntime()
        repeat(3) { System.gc(); Thread.sleep(50) }
        return rt.totalMemory() - rt.freeMemory()
    }

    private fun hands(seed: Int, n: Int): List<HandScript> {
        val rnd = Random(seed)
        val lines = listOf(
            PreflopAction(PreflopLine.RFI_CALL, Position.CO, Position.BB), PreflopAction(PreflopLine.RFI_CALL, Position.BTN, Position.BB),
            PreflopAction(PreflopLine.RFI_CALL, Position.UTG, Position.BTN), PreflopAction(PreflopLine.THREE_BET_CALL, Position.CO, Position.BTN),
            PreflopAction(PreflopLine.RFI_COLD_CALL_BB_CALL, Position.CO, Position.BTN),
        )
        return List(n) {
            val pf = lines[rnd.nextInt(lines.size)]
            val c = (0 until 52).shuffled(rnd).take(7).map { Card.toString(it) }
            val street = { if (pf.third != null) StreetScript(StreetKind.MULTIWAY_BET_CALL, Seat.OPENER)
                else listOf(StreetScript(StreetKind.BET_CALL, Seat.OPENER), StreetScript(StreetKind.CHECK_CHECK), StreetScript(StreetKind.BET_CALL, Seat.RESPONDER))[rnd.nextInt(3)] }
            HandScript(Stake.entries[rnd.nextInt(Stake.entries.size)], pf, Seat.OPENER, c[0] + c[1], c[2] + c[3] + c[4], c[5], c[6],
                street(), street(), RiverScript(listOf(null, 0.75)[rnd.nextInt(2)]))
        }
    }

    private fun pct(xs: List<Double>, q: Double): Double { val s = xs.sorted(); return s[((s.size - 1) * q).toInt()] }

    @Test fun twentyFullHandsLatencyAndHeap() {
        engine.play(hands(1, 1)[0]) // warm-up: preflop matrices + JIT
        val before = usedHeap()
        val perStreet = Street.entries.associateWith { ArrayList<Double>() }
        val totals = ArrayList<Double>()
        var peak = before
        for (script in hands(20261117, 20)) {
            val r = engine.play(script)
            PipelineInvariants.all(r, "perf ${script.flop}")
            for (rep in r.reports) perStreet.getValue(rep.street) += rep.latencyNanos / 1e6
            totals += r.totalNanos / 1e6
            val rt = Runtime.getRuntime(); peak = maxOf(peak, rt.totalMemory() - rt.freeMemory())
        }
        val after = usedHeap()
        // Second batch: retained heap must not keep growing (no leak across hands).
        for (script in hands(77, 20)) engine.play(script)
        val after2 = usedHeap()
        for ((s, xs) in perStreet) if (xs.isNotEmpty())
            println("PERF %-7s n=%2d mean=%7.1f ms p95=%7.1f ms max=%7.1f ms".format(s, xs.size, xs.average(), pct(xs, 0.95), xs.max()))
        println("PERF hand    mean=%7.1f ms p95=%7.1f ms max=%7.1f ms".format(totals.average(), pct(totals, 0.95), totals.max()))
        println("HEAP baseline=%.1f MB after20=%.1f MB after40=%.1f MB peak=%.1f MB net20=%.1f MB drift=%.1f MB".format(
            before / 1e6, after / 1e6, after2 / 1e6, peak / 1e6, (after - before) / 1e6, (after2 - after) / 1e6))
        assertTrue(totals.size == 20)
        assertTrue(pct(totals, 0.95) < 3_000.0, "p95 full hand < 3 s on the JVM test host")
        assertTrue(perStreet.getValue(Street.RIVER).average() < 100.0, "river solves are interactive")
        assertTrue(after - before < 64_000_000L, "retained heap after 20 hands < 64 MB")
        assertTrue(after2 - after < 16_000_000L, "no heap drift between batches (no leak)")
        assertTrue(peak < Runtime.getRuntime().maxMemory(), "no OOM")
    }
}
