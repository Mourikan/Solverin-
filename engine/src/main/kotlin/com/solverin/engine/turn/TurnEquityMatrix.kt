package com.solverin.engine.turn

import com.solverin.engine.flop.EquityProvider
import com.solverin.engine.flop.FlopEquity
import com.solverin.engine.flop.Combos

/**
 * Exact turn equities (EQ46): every hero × villain pair over every river compatible with both hands, 16-bit
 * quantized, built from the [TurnRiverProjectionEngine] river strengths (no extra evaluation).
 */
class TurnEquityMatrix(val projection: TurnRiverProjectionEngine) : EquityProvider {
    constructor(board: IntArray, hero: FloatArray, villain: FloatArray, policy: TurnPolicy = TurnPolicy()) :
        this(TurnRiverProjectionEngine(board, hero, villain, policy))

    val heroIdx: IntArray get() = projection.heroIdx
    val villainIdx: IntArray get() = projection.villainIdx
    private val eq: ShortArray

    init {
        val p = projection
        val nh = p.heroIdx.size; val nv = p.villainIdx.size; val nr = p.rivers.size
        eq = ShortArray(nh * nv)
        for (a in 0 until nh) {
            val ha = a * nr
            for (b in 0 until nv) {
                if (p.hMask[a] and p.vMask[b] != 0L) { eq[a * nv + b] = BLOCKED; continue }
                val vb = b * nr
                var w = 0.0; var c = 0
                for (ri in 0 until nr) {
                    val x = p.hRiver[ha + ri]; val y = p.vRiver[vb + ri]
                    if (x < 0 || y < 0) continue
                    c++
                    if (x > y) w += 1.0 else if (x == y) w += 0.5
                }
                eq[a * nv + b] = if (c == 0) BLOCKED else Math.round(w / c * SCALE).toInt().coerceIn(0, SCALE).toShort()
            }
        }
    }

    val bytes: Int get() = eq.size * 2

    fun outs(villainRange: FloatArray, continueFraction: Double = 1.0): TurnRiverProjectionEngine.Projection =
        projection.project(villainRange, continueFraction)

    private fun pair(a: Int, b: Int): Float {
        val q = eq[a * villainIdx.size + b]
        return if (q == BLOCKED) -1f else (q.toInt() and 0xFFFF) * INV
    }

    override fun heroVs(villainRange: FloatArray): FlopEquity.Result {
        val win = DoubleArray(Combos.COUNT); val den = DoubleArray(Combos.COUNT)
        val vi = villainIdx
        val w = FloatArray(vi.size) { villainRange[vi[it]] }
        for (a in heroIdx.indices) {
            var n = 0.0; var d = 0.0
            for (b in vi.indices) {
                val wb = w[b]; if (wb <= 0f) continue
                val e = pair(a, b); if (e < 0f) continue
                n += wb * e; d += wb
            }
            win[heroIdx[a]] = n; den[heroIdx[a]] = d
        }
        return FlopEquity.Result(win, den)
    }

    override fun villainVs(heroRange: FloatArray): FlopEquity.Result {
        val win = DoubleArray(Combos.COUNT); val den = DoubleArray(Combos.COUNT)
        val hi = heroIdx
        val w = FloatArray(hi.size) { heroRange[hi[it]] }
        for (b in villainIdx.indices) {
            var n = 0.0; var d = 0.0
            for (a in hi.indices) {
                val wa = w[a]; if (wa <= 0f) continue
                val e = pair(a, b); if (e < 0f) continue
                n += wa * (1f - e); d += wa
            }
            win[villainIdx[b]] = n; den[villainIdx[b]] = d
        }
        return FlopEquity.Result(win, den)
    }

    companion object {
        private const val SCALE = 65534
        private const val INV = 1f / SCALE
        private const val BLOCKED: Short = -1
        const val UNSEEN_RIVERS = TurnRiverProjectionEngine.UNSEEN_RIVERS
    }
}
