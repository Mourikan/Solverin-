package com.solverin.engine.turn

import com.solverin.engine.eval.HandEvaluator
import com.solverin.engine.flop.Combos
import com.solverin.engine.flop.EquityProvider
import com.solverin.engine.flop.FlopEquity
import java.util.IdentityHashMap

/**
 * Exact turn equities: every hero × villain pair is evaluated on every river compatible with both hands
 * (46 unseen cards from hero's view), 16-bit quantized. River hand strengths of every combo are kept so that
 * outs can be measured exactly against any villain sub-range (betting / calling range):
 *  - hero behind now (turn showdown share < 0.5): a river is an out when hero's win share on it ≥ outThreshold;
 *    clean when ≥ cleanOutThreshold (wins vs almost all the range), dirty otherwise (villain often holds a
 *    better completed hand → reverse implied odds);
 *  - hero ahead now: rivers where the win share falls below 1 − outThreshold are villain's clean outs.
 */
class TurnEquityMatrix(
    private val board: IntArray, hero: FloatArray, villain: FloatArray,
    private val outThreshold: Double = 0.6, private val cleanOutThreshold: Double = 0.85,
) : EquityProvider {
    val heroIdx: IntArray
    val villainIdx: IntArray
    private val eq: ShortArray
    private val rivers: IntArray
    /** River hand strength per (combo, river), −1 when the combo holds the river card. */
    private val hRiver: IntArray
    private val vRiver: IntArray
    private val hTurn: IntArray
    private val vTurn: IntArray
    private val hMask: LongArray
    private val vMask: LongArray
    private val outsCache = IdentityHashMap<FloatArray, Outs>()

    /**
     * Per hero combo vs one villain range: [aheadNow] turn showdown share; [clean] / [dirty] counts of
     * outs among the 46 unseen rivers; [cleanWin] Σ win share over clean outs; [dirtyLoss] Σ (1 − share)
     * over dirty outs; [villainClean] rivers that flip a hand hero is winning now.
     */
    class Outs(
        val aheadNow: FloatArray, val clean: IntArray, val dirty: IntArray,
        val cleanWin: FloatArray, val dirtyLoss: FloatArray, val villainClean: IntArray,
    ) {
        fun behind(h: Int): Boolean = aheadNow[h] < 0.5f
    }

    init {
        require(board.size == 4)
        val dead = Combos.mask(board)
        heroIdx = (0 until Combos.COUNT).filter { hero[it] > 0f && Combos.isLive(it, dead) }.toIntArray()
        villainIdx = (0 until Combos.COUNT).filter { villain[it] > 0f && Combos.isLive(it, dead) }.toIntArray()
        rivers = (0 until 52).filter { dead and (1L shl it) == 0L }.toIntArray()
        val nh = heroIdx.size; val nv = villainIdx.size; val nr = rivers.size
        hMask = LongArray(nh) { Combos.MASK[heroIdx[it]] }
        vMask = LongArray(nv) { Combos.MASK[villainIdx[it]] }
        val ev = HandEvaluator()
        val six = IntArray(6); for (i in 0 until 4) six[i] = board[i]
        hTurn = IntArray(nh) { a -> six[4] = Combos.C1[heroIdx[a]]; six[5] = Combos.C2[heroIdx[a]]; ev.evaluate(six, 6) }
        vTurn = IntArray(nv) { b -> six[4] = Combos.C1[villainIdx[b]]; six[5] = Combos.C2[villainIdx[b]]; ev.evaluate(six, 6) }
        val cards = IntArray(7); for (i in 0 until 4) cards[i] = board[i]
        hRiver = IntArray(nh * nr); vRiver = IntArray(nv * nr)
        for ((ri, r) in rivers.withIndex()) {
            cards[4] = r
            val rm = 1L shl r
            for (a in 0 until nh) {
                hRiver[a * nr + ri] = if (hMask[a] and rm != 0L) -1 else {
                    cards[5] = Combos.C1[heroIdx[a]]; cards[6] = Combos.C2[heroIdx[a]]; ev.evaluate(cards, 7)
                }
            }
            for (b in 0 until nv) {
                vRiver[b * nr + ri] = if (vMask[b] and rm != 0L) -1 else {
                    cards[5] = Combos.C1[villainIdx[b]]; cards[6] = Combos.C2[villainIdx[b]]; ev.evaluate(cards, 7)
                }
            }
        }
        eq = ShortArray(nh * nv)
        for (a in 0 until nh) {
            val ha = a * nr
            for (b in 0 until nv) {
                if (hMask[a] and vMask[b] != 0L) { eq[a * nv + b] = BLOCKED; continue }
                val vb = b * nr
                var w = 0.0; var c = 0
                for (ri in 0 until nr) {
                    val x = hRiver[ha + ri]; val y = vRiver[vb + ri]
                    if (x < 0 || y < 0) continue
                    c++
                    if (x > y) w += 1.0 else if (x == y) w += 0.5
                }
                eq[a * nv + b] = if (c == 0) BLOCKED else Math.round(w / c * SCALE).toInt().coerceIn(0, SCALE).toShort()
            }
        }
    }

    val bytes: Int get() = eq.size * 2 + (hRiver.size + vRiver.size) * 4

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

    /** Exact outs of every hero combo against [villainRange] (memoized by array identity). */
    fun outs(villainRange: FloatArray): Outs = outsCache.getOrPut(villainRange) { computeOuts(villainRange) }

    private fun computeOuts(range: FloatArray): Outs {
        val nh = heroIdx.size; val nv = villainIdx.size; val nr = rivers.size
        val w = FloatArray(nv) { range[villainIdx[it]] }
        val ahead = FloatArray(Combos.COUNT); val clean = IntArray(Combos.COUNT); val dirty = IntArray(Combos.COUNT)
        val cleanWin = FloatArray(Combos.COUNT); val dirtyLoss = FloatArray(Combos.COUNT); val vOuts = IntArray(Combos.COUNT)
        for (a in 0 until nh) {
            val h = heroIdx[a]
            var n = 0.0; var d = 0.0
            for (b in 0 until nv) {
                val wb = w[b]
                if (wb <= 0f || hMask[a] and vMask[b] != 0L) continue
                d += wb
                if (hTurn[a] > vTurn[b]) n += wb else if (hTurn[a] == vTurn[b]) n += 0.5 * wb
            }
            if (d <= 0.0) { ahead[h] = 0.5f; continue }
            val now = (n / d).toFloat()
            ahead[h] = now
            val behind = now < 0.5f
            for (ri in 0 until nr) {
                val x = hRiver[a * nr + ri]
                if (x < 0) continue
                var rw = 0.0; var rd = 0.0
                for (b in 0 until nv) {
                    val wb = w[b]
                    if (wb <= 0f || hMask[a] and vMask[b] != 0L) continue
                    val y = vRiver[b * nr + ri]
                    if (y < 0) continue
                    rd += wb
                    if (x > y) rw += wb else if (x == y) rw += 0.5 * wb
                }
                if (rd <= 0.0) continue
                val share = rw / rd
                if (behind) {
                    if (share >= cleanOutThreshold) { clean[h]++; cleanWin[h] += share.toFloat() }
                    else if (share >= outThreshold) { dirty[h]++; dirtyLoss[h] += (1 - share).toFloat() }
                } else if (share < 1 - outThreshold) vOuts[h]++
            }
        }
        return Outs(ahead, clean, dirty, cleanWin, dirtyLoss, vOuts)
    }

    companion object {
        private const val SCALE = 65534
        private const val INV = 1f / SCALE
        private const val BLOCKED: Short = -1
        /** Unseen cards from hero's view on the turn: 52 − 4 board − 2 hole. */
        const val UNSEEN_RIVERS = 46
    }
}
