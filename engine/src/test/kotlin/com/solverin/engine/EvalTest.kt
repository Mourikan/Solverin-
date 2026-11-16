package com.solverin.engine

import com.solverin.engine.core.Card
import com.solverin.engine.core.HandClass
import com.solverin.engine.eval.EquityTable
import com.solverin.engine.eval.HandEvaluator
import com.solverin.engine.model.HandRanking
import com.solverin.engine.model.Partition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvalTest {
    private val ev = HandEvaluator()
    private fun e(vararg c: String) = ev.evaluate(c.map { Card.parse(it) }.toIntArray(), c.size)
    private val t = EquityTable.get()
    private fun eq(a: String, b: String) = t.equity(HandClass.parse(a), HandClass.parse(b))

    @Test fun categories() {
        val sf = e("Ah", "Kh", "Qh", "Jh", "Th", "2c", "3d")
        val quads = e("9c", "9d", "9h", "9s", "Ah", "2c", "3d")
        val fh = e("9c", "9d", "9h", "Ks", "Kh", "2c", "3d")
        val flush = e("2h", "7h", "9h", "Jh", "Kh", "2c", "3d")
        val wheel = e("Ah", "2c", "3d", "4s", "5h", "9c", "Kd")
        val six = e("2h", "3c", "4d", "5s", "6h", "9c", "Kd")
        val trips = e("9c", "9d", "9h", "Ks", "2h", "4c", "7d")
        val twoPair = e("9c", "9d", "Kh", "Ks", "2h", "4c", "7d")
        val pair = e("9c", "9d", "Ah", "Ks", "2h", "4c", "7d")
        val high = e("Ac", "9d", "Jh", "Ks", "2h", "4c", "7d")
        val ordered = listOf(sf, quads, fh, flush, six, wheel, trips, twoPair, pair, high)
        assertEquals(ordered, ordered.sortedDescending())
        assertEquals(e("Ac", "Kd", "9h", "9s", "2h", "4c", "7d") > e("Qc", "Jd", "9h", "9s", "2h", "4c", "7d"), true)
    }

    @Test fun equityTable() {
        assertEquals(0.82f, eq("AA", "KK"), 0.01f)
        assertEquals(0.46f, eq("AKs", "QQ"), 0.015f)
        assertEquals(0.525f, eq("22", "AKo"), 0.015f)
        assertEquals(0.852f, t.vsRandom[HandClass.parse("AA")], 0.01f)
        assertEquals(0.346f, t.vsRandom[HandClass.parse("72o")], 0.015f)
        assertEquals(1f, eq("AKo", "QJs") + eq("QJs", "AKo"), 0.001f)
    }

    @Test fun partitionConservesRange() {
        val r = HandRanking(t)
        val prior = FloatArray(HandClass.COUNT) { 1f }
        val p = Partition.split(prior, 0.1, 0.2, 0.6, r)
        fun combos(w: FloatArray) = w.withIndex().sumOf { (i, x) -> (x * HandClass.combos(i)).toDouble() }
        assertEquals(132.6, combos(p.raise), 0.5)
        assertEquals(265.2, combos(p.call), 0.5)
        assertEquals(1326.0, combos(p.prior), 0.5)
        assertTrue(p.raise[HandClass.parse("AA")] == 1f)
        val lastCall = r.byStrength.indexOfLast { p.call[it] > 0f }
        assertTrue(r.byStrength.drop(lastCall + 1).any { p.raise[it] > 0f }, "polar bluffs drawn below the call band")
    }
}
