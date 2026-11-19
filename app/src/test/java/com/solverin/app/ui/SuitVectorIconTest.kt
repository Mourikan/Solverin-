package com.solverin.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class SuitVectorIconTest {

    @Test
    fun diamondIsAStraightSidedSymmetricRhombusInsideTheUnitSquare() {
        val v = DIAMOND_VERTICES
        assertEquals(4, v.size)
        for (p in v) assertTrue("$p inside the unit square", p.x in 0f..1f && p.y in 0f..1f)
        val (top, right, bottom, left) = v
        // Vertical axis: top / bottom centred; horizontal axis: left / right mirrored at the same height.
        assertEquals(0.5f, top.x, 1e-6f); assertEquals(0.5f, bottom.x, 1e-6f)
        assertEquals(right.y, left.y, 1e-6f); assertEquals(0.5f, right.y, 1e-6f)
        assertEquals(1f, right.x + left.x, 1e-6f)
        assertEquals(1f, top.y + bottom.y, 1e-6f)
        // Four equal sides (a rhombus), taller than wide (card diamond).
        val sides = (0 until 4).map { i -> val a = v[i]; val b = v[(i + 1) % 4]; hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) }
        for (s in sides) assertEquals(sides[0], s, 1e-6)
        assertTrue(bottom.y - top.y > right.x - left.x)
        assertEquals(Suit.DIAMONDS, Suit.of(1))
        assertEquals(0xFF1FC7D4.toInt(), Suit.DIAMONDS.color.value.shr(32).toInt())
    }
}
