package com.solverin.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solverin.app.presentation.HandViewState
import com.solverin.app.presentation.SeatView
import com.solverin.app.presentation.TableStreet
import com.solverin.engine.core.Card
import kotlin.math.cos
import kotlin.math.sin

/** Street progress + 6-max oval with seats (halo on the player to act), hero hole cards and the board. */
@Composable
fun CompactTable6Max(
    state: HandViewState,
    onHeroCardTap: (Int) -> Unit,
    onBoardCardTap: (Int) -> Unit,
    onSeatTap: (com.solverin.engine.core.Position) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StreetProgress(state.street, Modifier.fillMaxWidth().height(18.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val w = maxWidth; val h = maxHeight
            val seatW = (w / 5.6f).coerceAtMost(80.dp); val seatH = (h / 2.9f).coerceAtMost(48.dp)
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.80f)
                    .fillMaxHeight(0.70f)
                    .clip(RoundedCornerShape(50))
                    .background(CakeColors.BackgroundAlt)
                    .border(2.dp, CakeColors.Purple.copy(alpha = 0.6f), RoundedCornerShape(50)),
            )
            CenterCards(state, onHeroCardTap, onBoardCardTap, Modifier.align(Alignment.Center))
            run {
                val heroIdx = state.seats.indexOfFirst { it.isHero }.coerceAtLeast(0)
                state.seats.forEachIndexed { i, seat ->
                    // Clockwise ring with hero at the bottom centre.
                    val angle = Math.PI / 2 + 2 * Math.PI * ((i - heroIdx + 6) % 6) / 6.0
                    val cx = w / 2 + (w / 2 - seatW / 2) * cos(angle).toFloat()
                    val cy = h / 2 + (h / 2 - seatH / 2) * sin(angle).toFloat()
                    SeatBadge(seat, Modifier.offset(cx - seatW / 2, cy - seatH / 2).size(seatW, seatH).clickable { onSeatTap(seat.position) })
                }
            }
        }
    }
}

@Composable
private fun StreetProgress(street: TableStreet, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TableStreet.entries.filter { it != TableStreet.SHOWDOWN || street == TableStreet.SHOWDOWN }.forEach { s ->
            val done = s.ordinal < street.ordinal
            val now = s == street
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(9.dp))
                    .background(when { now -> CakeColors.Cyan; done -> CakeColors.Purple; else -> CakeColors.Card })
                    .border(1.dp, CakeColors.CardBorder, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(s.name, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                    color = when { now -> CakeColors.Ink; done -> CakeColors.TextPrimary; else -> CakeColors.TextSecondary })
            }
        }
    }
}

@Composable
private fun SeatBadge(seat: SeatView, modifier: Modifier) {
    val border = when {
        seat.isToAct -> CakeColors.Cyan
        seat.isSelected -> CakeColors.Gold
        seat.isHero -> CakeColors.Purple
        else -> CakeColors.CardBorder
    }
    Column(
        modifier
            .alpha(if (seat.inHand) 1f else 0.4f)
            .then(if (seat.isToAct) Modifier.border(4.dp, CakeColors.Cyan.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(2.dp) else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .background(if (seat.isHero) CakeColors.Purple.copy(alpha = 0.35f) else CakeColors.Card)
            .border(if (seat.isToAct) 2.dp else 1.5.dp, border, RoundedCornerShape(12.dp))
            .padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(seat.label + if (seat.isHero) "★" else "", color = if (seat.isToAct) CakeColors.Cyan else CakeColors.TextPrimary,
            fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, maxLines = 1)
        val status = when {
            !seat.inHand -> "FOLD"
            seat.allIn -> "ALL-IN"
            seat.status.isNotEmpty() -> seat.status
            else -> "—"
        }
        Text(status, color = if (seat.inHand) CakeColors.TextSecondary else CakeColors.Pink, fontSize = 8.sp, fontFamily = Mono,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${bb(seat.stackBb)}" + if (seat.investBb > 0) " ·${bb(seat.investBb)}" else "", color = CakeColors.Gold, fontSize = 8.sp,
            fontFamily = Mono, maxLines = 1)
    }
}

@Composable
private fun CenterCards(state: HandViewState, onHeroCardTap: (Int) -> Unit, onBoardCardTap: (Int) -> Unit, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until 5) CardSlot(state.boardIds.getOrNull(i), 22.dp, enabled = i < state.boardIds.size) { onBoardCardTap(i) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.End) {
                Text("Pot ${bb(state.potBb)}", color = CakeColors.Gold, fontSize = 9.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
                Text("SPR ${spr(state.spr)}", color = CakeColors.TextSecondary, fontSize = 8.sp, fontFamily = Mono)
            }
            Spacer(Modifier.width(4.dp))
            for (i in 0 until 2) CardSlot(state.heroCardIds.getOrNull(i), 22.dp, enabled = i < state.heroCardIds.size, hero = true) { onHeroCardTap(i) }
        }
    }
}

/** One card slot (empty = dashed placeholder); four-colour suits. */
@Composable
fun CardSlot(card: Int?, width: Dp, enabled: Boolean, hero: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier.width(width).aspectRatio(0.72f).clip(shape)
            .background(if (card == null) CakeColors.Background else CakeColors.TextPrimary)
            .border(1.dp, if (hero) CakeColors.Purple else CakeColors.CardBorder, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (card != null) {
            val suit = Card.suit(card)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Card.RANK_CHARS[Card.rank(card)].toString(), color = CakeColors.Ink, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, lineHeight = 11.sp)
                Text(CakeColors.suitSymbols[suit], color = if (suit == 3) CakeColors.Ink else CakeColors.suitColors[suit], fontSize = 10.sp, lineHeight = 10.sp)
            }
        }
    }
}
