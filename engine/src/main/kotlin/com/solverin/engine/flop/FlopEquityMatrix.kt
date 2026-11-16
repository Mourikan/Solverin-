package com.solverin.engine.flop

import com.solverin.engine.eval.HandEvaluator

/** Source of combo-vs-range equities on one flop. */
interface EquityProvider {
    /** Equity of every hero combo vs [villainRange] (subset of the villain combos the provider was built for). */
    fun heroVs(villainRange: FloatArray): FlopEquity.Result
    /** Equity of every villain combo vs [heroRange]. */
    fun villainVs(heroRange: FloatArray): FlopEquity.Result
}

/** Runout enumeration per query (no pre-computation). */
class RunoutEquityProvider(private val board: IntArray, private val hero: FloatArray, private val villain: FloatArray,
                           private val equity: FlopEquity) : EquityProvider {
    override fun heroVs(villainRange: FloatArray) = equity.handsVsRange(board, villainRange, hero)
    override fun villainVs(heroRange: FloatArray) = equity.handsVsRange(board, heroRange, villain)
}

/**
 * Pairwise hero-combo × villain-combo equity on a board, built once over turn+river runouts (exact card
 * removal per pair) and quantized to 16 bits (≈ 2 bytes × nHero × nVillain, ~0.5–1 MB for full ranges).
 * Any range equity afterwards is a weighted row/column sum (≈ 1 ms), so villain node locks recompute
 * Max-EV instantly without new runout enumeration.
 */
class FlopEquityMatrix(board: IntArray, hero: FloatArray, villain: FloatArray, stride: Int = FlopEquity.FAST_STRIDE) : EquityProvider {
    val heroIdx: IntArray
    val villainIdx: IntArray
    private val eq: ShortArray

    init {
        require(board.size == 3)
        val dead = Combos.mask(board)
        heroIdx = (0 until Combos.COUNT).filter { hero[it] > 0f && Combos.isLive(it, dead) }.toIntArray()
        villainIdx = (0 until Combos.COUNT).filter { villain[it] > 0f && Combos.isLive(it, dead) }.toIntArray()
        val nh = heroIdx.size; val nv = villainIdx.size
        val win = FloatArray(nh * nv)
        val count = ShortArray(nh * nv)
        val str = IntArray(Combos.COUNT)
        val cards = IntArray(7)
        cards[0] = board[0]; cards[1] = board[1]; cards[2] = board[2]
        val ev = HandEvaluator()
        val rest = (0 until 52).filter { dead and (1L shl it) == 0L }.toIntArray()
        val hMask = LongArray(nh) { Combos.MASK[heroIdx[it]] }
        val vMask = LongArray(nv) { Combos.MASK[villainIdx[it]] }
        val evalSet = (heroIdx + villainIdx).distinct().toIntArray()
        var runout = 0
        for (i in rest.indices) for (j in i + 1 until rest.size) {
            if (runout++ % stride != 0) continue
            val t = rest[i]; val r = rest[j]
            cards[3] = t; cards[4] = r
            val d5 = (1L shl t) or (1L shl r)
            for (k in evalSet) {
                if (Combos.MASK[k] and d5 != 0L) { str[k] = -1; continue }
                cards[5] = Combos.C1[k]; cards[6] = Combos.C2[k]
                str[k] = ev.evaluate(cards, 7)
            }
            for (a in 0 until nh) {
                val sh = str[heroIdx[a]]
                if (sh < 0) continue
                val m = hMask[a]
                val row = a * nv
                for (b in 0 until nv) {
                    if (m and vMask[b] != 0L) continue
                    val sv = str[villainIdx[b]]
                    if (sv < 0) continue
                    val idx = row + b
                    if (sh > sv) win[idx] += 1f else if (sh == sv) win[idx] += 0.5f
                    count[idx] = (count[idx] + 1).toShort()
                }
            }
        }
        eq = ShortArray(nh * nv) { idx ->
            val c = count[idx].toInt() and 0xFFFF
            if (c == 0) BLOCKED else Math.round(win[idx] / c * SCALE).toInt().coerceIn(0, SCALE).toShort()
        }
    }

    val bytes: Int get() = eq.size * 2

    private fun pair(a: Int, b: Int): Float {
        val q = eq[a * villainIdx.size + b]
        return if (q == BLOCKED) -1f else (q.toInt() and 0xFFFF) * INV
    }

    override fun heroVs(villainRange: FloatArray): FlopEquity.Result {
        val win = DoubleArray(Combos.COUNT); val den = DoubleArray(Combos.COUNT)
        val w = FloatArray(villainIdx.size) { villainRange[villainIdx[it]] }
        for (a in heroIdx.indices) {
            var n = 0.0; var d = 0.0
            for (b in villainIdx.indices) {
                val wb = w[b]
                if (wb <= 0f) continue
                val e = pair(a, b)
                if (e < 0f) continue
                n += wb * e; d += wb
            }
            win[heroIdx[a]] = n; den[heroIdx[a]] = d
        }
        return FlopEquity.Result(win, den)
    }

    override fun villainVs(heroRange: FloatArray): FlopEquity.Result {
        val win = DoubleArray(Combos.COUNT); val den = DoubleArray(Combos.COUNT)
        val w = FloatArray(heroIdx.size) { heroRange[heroIdx[it]] }
        for (b in villainIdx.indices) {
            var n = 0.0; var d = 0.0
            for (a in heroIdx.indices) {
                val wa = w[a]
                if (wa <= 0f) continue
                val e = pair(a, b)
                if (e < 0f) continue
                n += wa * (1f - e); d += wa
            }
            win[villainIdx[b]] = n; den[villainIdx[b]] = d
        }
        return FlopEquity.Result(win, den)
    }

    companion object {
        private const val SCALE = 65534
        private const val INV = 1f / SCALE
        private const val BLOCKED: Short = -1 // 0xFFFF
    }
}
