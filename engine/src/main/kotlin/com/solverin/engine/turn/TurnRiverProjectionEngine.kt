package com.solverin.engine.turn

import com.solverin.engine.eval.HandEvaluator
import com.solverin.engine.flop.Combos
import java.util.IdentityHashMap

/**
 * Exact river projection over the 46 unseen cards (52 − 4 board − 2 hole) for every hero combo.
 *
 * Per hero combo h and river c_r: EQ_river(h | c_r) = showdown share vs Range_V \ {c_r} on Board ∪ {c_r}.
 * Disjoint river classes (vs any villain sub-range, memoized by array identity):
 *  - O_clean: EQ_turn(h) < 0.5 and EQ_river ≥ cleanRiverEquity;
 *  - O_dirty: hero's hand category improves, dirtyLow ≤ EQ_river < dirtyHigh and the villain combos that improved
 *    their own category AND beat hero (full house / higher flush / higher straight) are ≥ dirtyNutShare of villain's
 *    estimated river continuation range vs a bet: Range_V · continueFraction (river call elasticity);
 *  - villain clean outs: EQ_turn(h) ≥ 0.5 and EQ_river < villainOutEquity.
 * Against the base (perceived) ranges:
 *  - S_bluff: hero's category does not improve and EQ_river < 0.5, RiverNutBlocker(h, c_r) < 1 (villain's
 *    river nut frequency after removing the combos h blocks, relative to the unblocked frequency) and
 *    ΔNuts_river(c_r | h) = heroNutShare(c_r) − villainNutFreq(c_r | h) exceeds the mean ΔNuts_river over the
 *    46 rivers by scareNutShift (the card swings the nut balance to hero's perceived range given h's blockers).
 */
class TurnRiverProjectionEngine(
    private val board: IntArray, private val heroW: FloatArray, private val villainW: FloatArray,
    private val policy: TurnPolicy = TurnPolicy(),
) {
    val heroIdx: IntArray
    val villainIdx: IntArray
    val rivers: IntArray
    internal val hRiver: IntArray
    internal val vRiver: IntArray
    private val hTurn: IntArray
    private val vTurn: IntArray
    internal val hMask: LongArray
    internal val vMask: LongArray
    private val cache = IdentityHashMap<FloatArray, HashMap<Double, Projection>>()

    /** ΔNuts_river per river (hero share of > riverNutEquity combos − villain share), base ranges. */
    val riverNutDelta: DoubleArray
    val meanRiverNutDelta: Double
    /** Nut share of hero's perceived range per river. */
    val heroRiverNutShare: DoubleArray
    val meanHeroRiverNutShare: Double
    /** |S_bluff| per hero combo (Combos index). */
    val scare = IntArray(Combos.COUNT)

    /**
     * [eqTurn] EQ46 vs the range; [clean] / [dirty] = |O_clean| / |O_dirty|; [trapped] = P(HeroTrapped | EQ_river):
     * mean (1 − EQ_river) over dirty rivers; [cleanEquity] mean EQ_river over clean rivers; [villainClean].
     */
    class Projection(
        val eqTurn: FloatArray, val clean: IntArray, val dirty: IntArray, val trapped: FloatArray,
        val cleanEquity: FloatArray, val villainClean: IntArray,
    ) {
        fun behind(h: Int): Boolean = eqTurn[h] < 0.5f
    }

    init {
        require(board.size == 4)
        val dead = Combos.mask(board)
        heroIdx = (0 until Combos.COUNT).filter { heroW[it] > 0f && Combos.isLive(it, dead) }.toIntArray()
        villainIdx = (0 until Combos.COUNT).filter { villainW[it] > 0f && Combos.isLive(it, dead) }.toIntArray()
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
            for (a in 0 until nh) hRiver[a * nr + ri] = if (hMask[a] and rm != 0L) -1 else {
                cards[5] = Combos.C1[heroIdx[a]]; cards[6] = Combos.C2[heroIdx[a]]; ev.evaluate(cards, 7)
            }
            for (b in 0 until nv) vRiver[b * nr + ri] = if (vMask[b] and rm != 0L) -1 else {
                cards[5] = Combos.C1[villainIdx[b]]; cards[6] = Combos.C2[villainIdx[b]]; ev.evaluate(cards, 7)
            }
        }
        // Base river equities: hero vs villain range, villain vs hero range.
        val hw = FloatArray(nh) { heroW[heroIdx[it]] }
        val vw = FloatArray(nv) { villainW[villainIdx[it]] }
        val hEq = FloatArray(nh * nr) { -1f }
        val vEq = FloatArray(nv * nr) { -1f }
        for (ri in 0 until nr) {
            for (a in 0 until nh) {
                val x = hRiver[a * nr + ri]; if (x < 0) continue
                var n = 0.0; var d = 0.0
                for (b in 0 until nv) {
                    val y = vRiver[b * nr + ri]
                    if (y < 0 || hMask[a] and vMask[b] != 0L) continue
                    d += vw[b]; if (x > y) n += vw[b] else if (x == y) n += 0.5 * vw[b]
                }
                if (d > 0) hEq[a * nr + ri] = (n / d).toFloat()
            }
            for (b in 0 until nv) {
                val y = vRiver[b * nr + ri]; if (y < 0) continue
                var n = 0.0; var d = 0.0
                for (a in 0 until nh) {
                    val x = hRiver[a * nr + ri]
                    if (x < 0 || hMask[a] and vMask[b] != 0L) continue
                    d += hw[a]; if (y > x) n += hw[a] else if (x == y) n += 0.5 * hw[a]
                }
                if (d > 0) vEq[b * nr + ri] = (n / d).toFloat()
            }
        }
        val nutEq = policy.riverNutEquity.toFloat()
        heroRiverNutShare = DoubleArray(nr)
        riverNutDelta = DoubleArray(nr) { ri ->
            var hn = 0.0; var ht = 0.0; var vn = 0.0; var vt = 0.0
            for (a in 0 until nh) { val e = hEq[a * nr + ri]; if (e < 0f) continue; ht += hw[a]; if (e > nutEq) hn += hw[a] }
            for (b in 0 until nv) { val e = vEq[b * nr + ri]; if (e < 0f) continue; vt += vw[b]; if (e > nutEq) vn += vw[b] }
            heroRiverNutShare[ri] = if (ht > 0) hn / ht else 0.0
            heroRiverNutShare[ri] - (if (vt > 0) vn / vt else 0.0)
        }
        meanRiverNutDelta = if (nr > 0) riverNutDelta.average() else 0.0
        meanHeroRiverNutShare = if (nr > 0) heroRiverNutShare.average() else 0.0
        // Scare rivers: ΔNuts_river conditioned on hero's blockers (villain nut frequency after card removal).
        val nutByCard = DoubleArray(52)
        val allByCard = DoubleArray(52)
        for (ri in 0 until nr) {
            nutByCard.fill(0.0); allByCard.fill(0.0)
            var nutTotal = 0.0; var allTotal = 0.0
            val nutOfCombo = HashMap<Int, Double>(); val allOfCombo = HashMap<Int, Double>()
            for (b in 0 until nv) {
                val e = vEq[b * nr + ri]
                if (e < 0f) continue
                val k = villainIdx[b]; val wb = vw[b].toDouble()
                allTotal += wb; allByCard[Combos.C1[k]] += wb; allByCard[Combos.C2[k]] += wb; allOfCombo[k] = wb
                if (e > nutEq) { nutTotal += wb; nutByCard[Combos.C1[k]] += wb; nutByCard[Combos.C2[k]] += wb; nutOfCombo[k] = wb }
            }
            if (nutTotal <= 0.0 || allTotal <= 0.0) continue
            val baseFreq = nutTotal / allTotal
            for (a in 0 until nh) {
                val x = hRiver[a * nr + ri]
                if (x < 0) continue
                if ((x ushr 20) > (hTurn[a] ushr 20) || hEq[a * nr + ri] >= 0.5f) continue
                val k = heroIdx[a]
                val remNut = nutTotal - nutByCard[Combos.C1[k]] - nutByCard[Combos.C2[k]] + (nutOfCombo[k] ?: 0.0)
                val remAll = allTotal - allByCard[Combos.C1[k]] - allByCard[Combos.C2[k]] + (allOfCombo[k] ?: 0.0)
                if (remAll <= 0.0) continue
                val freq = remNut / remAll
                val blocker = freq / baseFreq
                val deltaGivenH = heroRiverNutShare[ri] - freq
                if (blocker < 1.0 && deltaGivenH > meanRiverNutDelta + policy.scareNutShift) scare[k]++
            }
        }
    }

    /** River nut blocker reference [0, 1]: share of the 46 rivers that are scare cards for [h]. */
    fun scareShare(h: Int): Double = scare[h].toDouble() / UNSEEN_RIVERS

    /**
     * Projection vs [range]; [continueFraction] = share of that range estimated to continue vs a river bet (dirty-out
     * reference). Memoized by (array identity, fraction).
     */
    fun project(range: FloatArray, continueFraction: Double = 1.0): Projection {
        val cf = continueFraction.coerceIn(policy.minRiverContinue, 1.0)
        return cache.getOrPut(range) { HashMap() }.getOrPut(cf) { compute(range, cf) }
    }

    private fun compute(range: FloatArray, continueFraction: Double): Projection {
        val dirtyShare = policy.dirtyNutShare * continueFraction
        val nh = heroIdx.size; val nv = villainIdx.size; val nr = rivers.size
        val w = FloatArray(nv) { range[villainIdx[it]] }
        val eqTurn = FloatArray(Combos.COUNT); val clean = IntArray(Combos.COUNT); val dirty = IntArray(Combos.COUNT)
        val trapped = FloatArray(Combos.COUNT); val cleanEq = FloatArray(Combos.COUNT); val vOuts = IntArray(Combos.COUNT)
        for (a in 0 until nh) {
            val h = heroIdx[a]
            var tw = 0.0; var td = 0.0
            val shares = DoubleArray(nr) { -1.0 }
            val dominated = DoubleArray(nr)
            for (ri in 0 until nr) {
                val x = hRiver[a * nr + ri]
                if (x < 0) continue
                val hc = x ushr 20
                var rw = 0.0; var rd = 0.0; var dom = 0.0
                for (b in 0 until nv) {
                    val wb = w[b]
                    if (wb <= 0f || hMask[a] and vMask[b] != 0L) continue
                    val y = vRiver[b * nr + ri]
                    if (y < 0) continue
                    rd += wb
                    if (x > y) rw += wb
                    else if (x == y) rw += 0.5 * wb
                    else if ((y ushr 20) > (vTurn[b] ushr 20) && (y ushr 20) >= hc) dom += wb
                }
                if (rd <= 0.0) continue
                shares[ri] = rw / rd; dominated[ri] = dom / rd
                tw += rw; td += rd
            }
            if (td <= 0.0) { eqTurn[h] = 0.5f; continue }
            val eq = (tw / td).toFloat()
            eqTurn[h] = eq
            var ce = 0.0; var tr = 0.0
            for (ri in 0 until nr) {
                val s = shares[ri]
                if (s < 0.0) continue
                val improves = (hRiver[a * nr + ri] ushr 20) > (hTurn[a] ushr 20)
                when {
                    eq < 0.5f && s >= policy.cleanRiverEquity -> { clean[h]++; ce += s }
                    improves && s >= policy.dirtyLow && s < policy.dirtyHigh && dominated[ri] >= dirtyShare -> { dirty[h]++; tr += 1 - s }
                    eq >= 0.5f && s < policy.villainOutEquity -> vOuts[h]++
                }
            }
            if (clean[h] > 0) cleanEq[h] = (ce / clean[h]).toFloat()
            if (dirty[h] > 0) trapped[h] = (tr / dirty[h]).toFloat()
        }
        return Projection(eqTurn, clean, dirty, trapped, cleanEq, vOuts)
    }

    companion object {
        const val UNSEEN_RIVERS = 46
    }
}
