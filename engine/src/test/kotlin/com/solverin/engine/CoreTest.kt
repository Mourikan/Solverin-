package com.solverin.engine

import com.solverin.engine.core.Card
import com.solverin.engine.core.HandClass
import com.solverin.engine.range.RangeParser
import com.solverin.engine.range.Scenario
import com.solverin.engine.range.SpotKey
import com.solverin.engine.core.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreTest {
    @Test fun handClassLayout() {
        assertEquals(1326, (0 until HandClass.COUNT).sumOf { HandClass.combos(it) })
        assertEquals(1326, HandClass.OFFSETS[HandClass.COUNT])
        for (h in 0 until HandClass.COUNT) {
            assertEquals(h, HandClass.parse(HandClass.name(h)))
            assertEquals(HandClass.combos(h), HandClass.OFFSETS[h + 1] - HandClass.OFFSETS[h])
        }
        assertEquals(0, HandClass.parse("AA"))
        assertEquals(1, HandClass.parse("AKs"))
        assertEquals(13, HandClass.parse("AKo"))
        assertEquals(HandClass.parse("AKs"), HandClass.fromCards(Card.parse("Ah"), Card.parse("Kh")))
    }

    @Test fun parser() {
        fun combos(n: String) = RangeParser.parse(n).withIndex().sumOf { (i, w) -> (w * HandClass.combos(i)).toDouble() }
        assertEquals(78.0, combos("22+"))
        assertEquals(16.0, combos("ATs+"))
        assertEquals(16.0, combos("A5s-A2s"))
        assertEquals(16.0, combos("AK"))
        assertEquals(36.0, combos("T9o-T7o"))
        assertEquals(30.0, combos("99-55"))
        val w = RangeParser.parse("AKs,AKs:0.5")
        assertEquals(0.5f, w[HandClass.parse("AKs")])
    }

    @Test fun spotKey() {
        val k = SpotKey.of(Scenario.SQUEEZE, Position.BB, Position.CO, Position.BTN)
        assertEquals(Scenario.SQUEEZE, k.scenario)
        assertEquals(Position.BB, k.hero)
        assertEquals(Position.CO, k.villain)
        assertEquals(Position.BTN, k.caller)
        assertTrue(SpotKey.of(Scenario.RFI, Position.UTG).caller == null)
    }
}
