package com.solverin.engine.range

import com.solverin.engine.core.Card
import com.solverin.engine.core.HandClass

/**
 * Standard range notation -> per-class weights.
 * Tokens (comma separated, later tokens override earlier):
 *   AA  AKs  AKo  AK(both)  TT+  ATs+  KTo+  A5s-A2s  99-66  T9o-T7o   optional ":0.5" weight.
 */
object RangeParser {

    fun parse(notation: String): FloatArray {
        val w = FloatArray(HandClass.COUNT)
        if (notation.isBlank()) return w
        for (raw in notation.split(',')) {
            val token = raw.trim()
            if (token.isEmpty()) continue
            val parts = token.split(':')
            val weight = if (parts.size > 1) parts[1].trim().toFloat() else 1f
            require(weight in 0f..1f) { "Weight out of range in '$token'" }
            for (h in expand(parts[0].trim())) w[h] = weight
        }
        return w
    }

    fun expand(t: String): List<Int> {
        if (t.contains('-')) return expandDash(t)
        val plus = t.endsWith('+')
        val body = if (plus) t.dropLast(1) else t
        val a = Card.rankOf(body[0]); val b = Card.rankOf(body[1])
        val suffix = body.getOrNull(2)?.lowercaseChar()
        if (a == b) {
            return if (plus) (a..12).map { HandClass.index(it, it, false) } else listOf(HandClass.index(a, a, false))
        }
        val hi = maxOf(a, b); val lo = minOf(a, b)
        val kickers = if (plus) (lo until hi) else (lo..lo)
        return kickers.flatMap { k -> classes(hi, k, suffix) }
    }

    private fun expandDash(t: String): List<Int> {
        val (l, r) = t.split('-').map { it.trim() }
        val a1 = Card.rankOf(l[0]); val b1 = Card.rankOf(l[1])
        val a2 = Card.rankOf(r[0]); val b2 = Card.rankOf(r[1])
        if (a1 == b1) {
            require(a2 == b2) { "Bad pair range '$t'" }
            return (minOf(a1, a2)..maxOf(a1, a2)).map { HandClass.index(it, it, false) }
        }
        require(a1 == a2) { "Range must share high card: '$t'" }
        val suffix = l.getOrNull(2)?.lowercaseChar()
        return (minOf(b1, b2)..maxOf(b1, b2)).flatMap { k -> classes(a1, k, suffix) }
    }

    private fun classes(hi: Int, lo: Int, suffix: Char?): List<Int> = when (suffix) {
        's' -> listOf(HandClass.index(hi, lo, true))
        'o' -> listOf(HandClass.index(hi, lo, false))
        null -> listOf(HandClass.index(hi, lo, true), HandClass.index(hi, lo, false))
        else -> throw IllegalArgumentException("Bad suffix '$suffix'")
    }
}
