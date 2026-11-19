package com.solverin.engine.math

import com.solverin.engine.core.HandClass
import com.solverin.engine.eval.CardRemoval
import com.solverin.engine.eval.HandEvaluator
import com.solverin.engine.model.HandRanking

/**
 * EV_implied: hand-specific chips won/lost postflop beyond the pot share counted by EQ·R·Pot.
 * R_real already prices the AVERAGE hand's postflop flow, so the term is centered on the
 * combo-weighted average hand (same villain range, pot, stack, position) and scaled by SPR depth:
 *
 *   raw(h)      = hit(h)·payoff(V|h)·extract(pos)              hero makes a strong hand, villain pays
 *               − hit(V|h)·pays(h)·reverse(pos)                 villain makes a strong hand, hero pays
 *   (kicker domination is priced inside R_real via DominationPenalty)
 *   EV_implied  = depth(SPR)·(raw(h) − E_combos[raw])           depth = clamp((SPR − 1) / 5, 0, 1)
 *
 *  hit(h):   P(strong made hand on the flop) + DRAW_COMPLETION·P(strong draw on the flop), exact over all
 *            C(50,3) flops (rank enumeration for sets / trips / two pair / straights / open-enders,
 *            exact suit combinatorics for flushes and flush draws). Nut-weighted.
 *  payoff:   share of villain's range (card removal = blockers) that stacks off with one pair.
 *  pays(h):  how often hero's own hand pays off a stronger made hand (overpairs / TPTK: 1 .. weak: 0.1).
 *  extract / reverse: min(stack behind, potTotal × factor); IP extracts more, OOP loses more.
 */
class ImpliedOdds(ranking: HandRanking) {
    val hit = FloatArray(HandClass.COUNT)
    private val payers = BooleanArray(HandClass.COUNT)
    private val pays = FloatArray(HandClass.COUNT)

    init {
        for (h in 0 until HandClass.COUNT) hit[h] = computeHit(h).toFloat()
        var acc = 0.0
        for (v in ranking.byStrength) {
            val pct = acc / HandClass.TOTAL_COMBOS
            if (pct < PAYER_FRACTION) payers[v] = true
            pays[v] = if (pct < PAYER_FRACTION) 1f
            else (1.0 - (pct - PAYER_FRACTION) / (PAYS_FLOOR_AT - PAYER_FRACTION) * 0.9).coerceIn(0.1, 1.0).toFloat()
            acc += HandClass.combos(v)
        }
    }

    fun payoff(h: Int, villain: FloatArray): Double {
        var all = 0.0; var pay = 0.0
        for (v in 0 until HandClass.COUNT) {
            val w = villain[v]
            if (w <= 0f) continue
            val c = w * CardRemoval.freeCombos(h, v)
            all += c
            if (payers[v]) pay += c
        }
        return if (all <= 0.0) 0.0 else pay / all
    }

    fun villainHit(h: Int, villain: FloatArray): Double {
        var all = 0.0; var s = 0.0
        for (v in 0 until HandClass.COUNT) {
            val w = villain[v]
            if (w <= 0f) continue
            val c = w * CardRemoval.freeCombos(h, v)
            all += c; s += c * hit[v]
        }
        return if (all <= 0.0) 0.0 else s / all
    }

    private data class MeanKey(val range: FloatArray, val pot: Double, val behind: Double, val ip: Boolean) {
        override fun equals(other: Any?) = other is MeanKey && other.range === range &&
            other.pot == pot && other.behind == behind && other.ip == ip
        override fun hashCode() = ((System.identityHashCode(range) * 31 + pot.hashCode()) * 31 + behind.hashCode()) * 31 + ip.hashCode()
    }

    private val means = HashMap<MeanKey, Double>()

    private fun mean(villain: FloatArray, potTotal: Double, behind: Double, inPosition: Boolean): Double {
        val k = MeanKey(villain, potTotal, behind, inPosition)
        synchronized(means) { means[k]?.let { return it } }
        var s = 0.0
        for (h in 0 until HandClass.COUNT) s += raw(h, villain, potTotal, behind, inPosition) * HandClass.combos(h)
        val m = s / HandClass.TOTAL_COMBOS
        synchronized(means) {
            if (means.size > MEAN_CACHE) means.clear()
            means[k] = m
        }
        return m
    }

    fun depth(potTotal: Double, behind: Double): Double =
        if (potTotal <= 0.0) 0.0 else ((behind / potTotal - 1.0) / DEPTH_SPAN).coerceIn(0.0, 1.0)

    fun ev(h: Int, villain: FloatArray, potTotal: Double, behind: Double, inPosition: Boolean): Double {
        val d = depth(potTotal, behind)
        if (d <= 0.0) return 0.0
        return d * (raw(h, villain, potTotal, behind, inPosition) - mean(villain, potTotal, behind, inPosition))
    }

    /**
     * Set-mining pairs (22 up to [SET_MINE_TOP_RANK]) are monotone: a higher small pair can always play exactly like a
     * lower one (set-mine and give up), so its implied term is never below any lower small pair's (otherwise 33
     * out-earns 44 through 44's larger reverse odds). Higher pairs keep their own term (they play as overpairs, not set-miners).
     */
    fun raw(h: Int, villain: FloatArray, potTotal: Double, behind: Double, inPosition: Boolean): Double {
        val own = single(h, villain, potTotal, behind, inPosition)
        if (!HandClass.isPair(h) || HandClass.highRank(h) > SET_MINE_TOP_RANK) return own
        var best = own
        for (r in 0 until HandClass.highRank(h)) best = maxOf(best, single(PAIR_BY_RANK[r], villain, potTotal, behind, inPosition))
        return best
    }

    private fun single(h: Int, villain: FloatArray, potTotal: Double, behind: Double, inPosition: Boolean): Double {
        if (behind <= 0.0) return 0.0
        val ext = minOf(behind, potTotal * if (inPosition) EXTRACT_IP else EXTRACT_OOP)
        val rev = minOf(behind, potTotal * if (inPosition) REVERSE_IP else REVERSE_OOP)
        val gain = hit[h] * payoff(h, villain) * ext
        val lossHit = villainHit(h, villain) * pays[h] * rev
        return gain - lossHit
    }

    companion object {
        /** Pair class of each rank (0 = 22 … 12 = AA). */
        private val PAIR_BY_RANK = IntArray(13).also { p ->
            for (h in 0 until HandClass.COUNT) if (HandClass.isPair(h)) p[HandClass.highRank(h)] = h
        }
        /** Highest set-mining pair rank (4 = 66). */
        const val SET_MINE_TOP_RANK = 4
        const val PAYER_FRACTION = 0.06
        const val PAYS_FLOOR_AT = 0.30
        const val EXTRACT_IP = 1.5
        const val EXTRACT_OOP = 1.0
        const val REVERSE_IP = 0.6
        const val REVERSE_OOP = 0.9
        const val DRAW_COMPLETION = 0.35
        const val DEPTH_SPAN = 5.0
        private const val MEAN_CACHE = 4096
        private const val W_SET = 1.0
        private const val W_TRIPS = 0.8
        private const val W_TWO_PAIR = 0.6
        private const val W_STRAIGHT = 0.9
        private const val W_OESD = 0.8

        private fun binom(n: Int, k: Int): Long {
            if (k < 0 || k > n) return 0
            var r = 1L
            for (i in 0 until k) r = r * (n - i) / (i + 1)
            return r
        }
        private fun c(n: Int, k: Int) = binom(n, k).toDouble()
        private val FLOPS = c(50, 3)

        /** P(flop pairs one of two unpaired hole cards) = 1 - C(44,3)/C(50,3). */
        val PAIR_ON_FLOP = 1.0 - c(44, 3) / FLOPS

        private fun heroStraight(board: Int, hero: Int): Boolean {
            val st = HandEvaluator.straightHigh(board or hero)
            return st >= 0 && st > HandEvaluator.straightHigh(board)
        }

        /** Open-ender: at least two distinct ranks complete a hero-dependent straight. */
        private fun oesd(board: Int, hero: Int): Boolean {
            var outs = 0
            for (r in 0 until 13) if ((board or hero) and (1 shl r) == 0 && heroStraight(board or (1 shl r), hero)) outs++
            return outs >= 2
        }

        private fun computeHit(h: Int): Double {
            val a = HandClass.highRank(h); val b = HandClass.lowRank(h)
            val pair = a == b
            val avail = IntArray(13) { 4 }
            avail[a]--; avail[b]--
            val heroMask = (1 shl a) or (1 shl b)
            val cnt = IntArray(13)
            var made = 0.0; var draw = 0.0; var total = 0.0
            fun rec(r: Int, left: Int, weight: Double) {
                if (r == 13) {
                    if (left != 0) return
                    total += weight
                    var board = 0
                    for (i in 0 until 13) if (cnt[i] > 0) board = board or (1 shl i)
                    var s = 0.0
                    if (pair) {
                        if (cnt[a] >= 1) s = W_SET
                    } else {
                        if (cnt[a] >= 2 || cnt[b] >= 2) s = W_TRIPS
                        else if (cnt[a] >= 1 && cnt[b] >= 1) s = W_TWO_PAIR
                    }
                    if (heroStraight(board, heroMask)) s = maxOf(s, W_STRAIGHT)
                    if (s > 0) made += weight * s
                    else if (oesd(board, heroMask)) draw += weight * W_OESD
                    return
                }
                for (k in 0..minOf(left, avail[r])) {
                    cnt[r] = k
                    rec(r + 1, left - k, weight * c(avail[r], k))
                }
                cnt[r] = 0
            }
            rec(0, 3, 1.0)
            check(kotlin.math.abs(total - FLOPS) < 0.5) { "flop enumeration mismatch" }
            val nutW = when (a) { 12 -> 1.0; 11 -> 0.8; else -> 0.6 }
            val (flushMade, flushDraw) = if (HandClass.isSuited(h)) {
                c(11, 3) / FLOPS * nutW to c(11, 2) * c(39, 1) / FLOPS * nutW
            } else if (!pair) {
                fun w(r: Int) = when (r) { 12 -> 0.9; 11 -> 0.5; else -> 0.25 }
                0.0 to c(12, 3) / FLOPS * (w(a) + w(b))
            } else 0.0 to 0.0
            val pMade = 1 - (1 - made / FLOPS) * (1 - flushMade)
            val pDraw = 1 - (1 - draw / FLOPS) * (1 - flushDraw)
            return pMade + DRAW_COMPLETION * pDraw * (1 - pMade)
        }
    }
}
