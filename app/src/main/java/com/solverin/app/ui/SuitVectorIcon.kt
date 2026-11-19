package com.solverin.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate

/** Card suit in engine order (Card.suit: 0 = clubs, 1 = diamonds, 2 = hearts, 3 = spades). */
@Immutable
enum class Suit(val color: Color) {
    CLUBS(Color(0xFF31D0AA)),
    DIAMONDS(Color(0xFF1FC7D4)),
    HEARTS(Color(0xFFFF4D6D)),
    SPADES(Color(0xFFF4EEFF));

    companion object {
        private val VALUES = entries.toTypedArray()
        fun of(engineSuit: Int): Suit = VALUES[engineSuit]
    }
}

/**
 * Suit drawn with vector paths (no font glyphs: Samsung One UI renders ♠♥♦♣ as colour emoji). Paths are defined on
 * a unit square, symmetric about x = 0.5, and scaled to the largest centred square of the canvas.
 */
@Composable
fun SuitVectorIcon(suit: Suit, modifier: Modifier = Modifier, tint: Color = suit.color) {
    Canvas(modifier) {
        val side = minOf(size.width, size.height)
        if (side <= 0f) return@Canvas
        val path = suitPath(suit, side)
        translate((size.width - side) / 2f, (size.height - side) / 2f) { drawPath(path, tint) }
    }
}

/** Diamond rhombus on the unit square (top, right, bottom, left): straight sides, centred at (0.5, 0.5). */
internal val DIAMOND_VERTICES: List<Offset> = listOf(Offset(0.5f, 0.03f), Offset(0.84f, 0.5f), Offset(0.5f, 0.97f), Offset(0.16f, 0.5f))

private fun suitPath(suit: Suit, s: Float): Path {
    fun x(v: Float) = v * s
    return Path().apply {
        when (suit) {
            Suit.HEARTS -> {
                moveTo(x(0.5f), x(0.92f))
                cubicTo(x(0.17f), x(0.68f), x(0.02f), x(0.49f), x(0.05f), x(0.29f))
                cubicTo(x(0.08f), x(0.09f), x(0.37f), x(0.02f), x(0.5f), x(0.25f))
                cubicTo(x(0.63f), x(0.02f), x(0.92f), x(0.09f), x(0.95f), x(0.29f))
                cubicTo(x(0.98f), x(0.49f), x(0.83f), x(0.68f), x(0.5f), x(0.92f))
                close()
            }
            Suit.DIAMONDS -> {
                // Pure rhombus: four straight, equal sides, symmetric about both axes (no curves, no glyph).
                val v = DIAMOND_VERTICES
                moveTo(x(v[0].x), x(v[0].y))
                for (i in 1 until v.size) lineTo(x(v[i].x), x(v[i].y))
                close()
            }
            Suit.SPADES -> {
                moveTo(x(0.5f), x(0.03f))
                cubicTo(x(0.80f), x(0.30f), x(0.98f), x(0.45f), x(0.95f), x(0.63f))
                cubicTo(x(0.92f), x(0.81f), x(0.67f), x(0.86f), x(0.54f), x(0.71f))
                quadraticBezierTo(x(0.56f), x(0.87f), x(0.70f), x(0.97f))
                lineTo(x(0.30f), x(0.97f))
                quadraticBezierTo(x(0.44f), x(0.87f), x(0.46f), x(0.71f))
                cubicTo(x(0.33f), x(0.86f), x(0.08f), x(0.81f), x(0.05f), x(0.63f))
                cubicTo(x(0.02f), x(0.45f), x(0.20f), x(0.30f), x(0.5f), x(0.03f))
                close()
            }
            Suit.CLUBS -> {
                val r = 0.2f
                addOval(Rect(Offset(x(0.5f), x(0.27f)), x(r)))
                addOval(Rect(Offset(x(0.27f), x(0.57f)), x(r)))
                addOval(Rect(Offset(x(0.73f), x(0.57f)), x(r)))
                addOval(Rect(Offset(x(0.5f), x(0.5f)), x(0.13f)))
                moveTo(x(0.465f), x(0.55f))
                quadraticBezierTo(x(0.45f), x(0.86f), x(0.30f), x(0.97f))
                lineTo(x(0.70f), x(0.97f))
                quadraticBezierTo(x(0.55f), x(0.86f), x(0.535f), x(0.55f))
                close()
            }
        }
    }
}
