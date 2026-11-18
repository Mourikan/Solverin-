package com.solverin.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solverin.app.presentation.ActKind

/** PancakeSwap / Cake DeFi palette. */
object CakeColors {
    val Background = Color(0xFF08060B)
    val BackgroundAlt = Color(0xFF191326)
    val Card = Color(0xFF27262C)
    val CardBorder = Color(0xFF383241)
    val Cyan = Color(0xFF1FC7D4)
    val CyanLip = Color(0xFF0098A1)
    val Purple = Color(0xFF7645D9)
    val Gold = Color(0xFFFFB237)
    val Mint = Color(0xFF31D0AA)
    val Pink = Color(0xFFED4B9E)
    val TextPrimary = Color(0xFFF4EEFF)
    val TextSecondary = Color(0xFFB8ADD2)
    val Ink = Color(0xFF08060B)

    val MaxEvGradient = Brush.horizontalGradient(listOf(Gold, Mint))
    val ScreenGradient = Brush.verticalGradient(listOf(Background, BackgroundAlt))

    fun action(kind: ActKind): Color = when (kind) {
        ActKind.FOLD -> Pink
        ActKind.CHECK, ActKind.CALL, ActKind.LIMP -> Cyan
        ActKind.BET, ActKind.RAISE, ActKind.ALL_IN -> Mint
    }

    fun lip(c: Color): Color = if (c == Cyan) CyanLip else lerp(c, Color.Black, 0.35f)

    /** Four-colour deck: clubs, diamonds, hearts, spades (engine suit order "cdhs"). */
    val suitColors = listOf(Mint, Cyan, Pink, TextPrimary)
    val suitSymbols = listOf("♣", "♦", "♥", "♠")
}

object CakeShapes {
    val Panel = RoundedCornerShape(20.dp)
    val Button = RoundedCornerShape(14.dp)
    val Chip = RoundedCornerShape(12.dp)
}

val Mono = FontFamily.Monospace

@Composable
fun CakeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = CakeColors.Cyan, secondary = CakeColors.Purple, tertiary = CakeColors.Mint,
            background = CakeColors.Background, surface = CakeColors.Card, onPrimary = CakeColors.Ink,
            onSecondary = CakeColors.TextPrimary, onBackground = CakeColors.TextPrimary, onSurface = CakeColors.TextPrimary,
            error = CakeColors.Pink, outline = CakeColors.CardBorder,
        ),
        content = content,
    )
}

/** Rounded 20dp panel: #27262C fill, 1.5dp #383241 border. */
@Composable
fun CakePanel(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .clip(CakeShapes.Panel)
            .background(CakeColors.Card)
            .border(1.5.dp, CakeColors.CardBorder, CakeShapes.Panel),
        content = content,
    )
}

/** Cake button: 14dp corners with a 3dp darker bottom lip that collapses when pressed. */
@Composable
fun CakeButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    textColor: Color = CakeColors.Ink,
    fontSize: TextUnit = 14.sp,
    fontFamily: FontFamily? = null,
    lip: Dp = 3.dp,
    maxLines: Int = 2,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val lipColor = CakeColors.lip(color)
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.35f)
            .clip(CakeShapes.Button)
            .background(lipColor)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = if (pressed) lip else 0.dp, bottom = if (pressed) 0.dp else lip)
                .clip(CakeShapes.Button)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text, color = textColor, fontWeight = FontWeight.ExtraBold, fontSize = fontSize, fontFamily = fontFamily,
                textAlign = TextAlign.Center, maxLines = maxLines, overflow = TextOverflow.Ellipsis, lineHeight = fontSize * 1.05f,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

fun bb(x: Double): String = if (kotlin.math.abs(x - Math.round(x)) < 1e-9) "${Math.round(x)}" else "%.1f".format(x)

fun signedBb(x: Double): String = if (!x.isFinite()) "—" else "%+.2f bb".format(x)

fun pct(f: Float): String = "%.1f%%".format(f * 100f)

val LabelStyle = TextStyle(color = CakeColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
