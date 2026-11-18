package com.solverin.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.solverin.engine.core.Position

private val StripShape = RoundedCornerShape(18.dp)

/**
 * Minimal street + cards strip (replaces the table drawing): street pill with progress, hero cards + position badge,
 * the 5 board slots (locked until the previous street closes) and chips of the players still in the pot.
 */
@Composable
fun CompactStreetAndBoardStrip(
    state: HandViewState,
    onHeroCardTap: (Int) -> Unit,
    onBoardCardTap: (Int) -> Unit,
    onSeatTap: (Position) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.clip(StripShape).background(CakeColors.Card).border(1.5.dp, CakeColors.CardBorder, StripShape).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth().weight(1.15f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StreetPill(state.street, Modifier.width(62.dp).fillMaxHeight())
            Row(
                Modifier.fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(CakeColors.Purple.copy(alpha = 0.25f)).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (i in 0 until 2) CardSlot(state.heroCardIds.getOrNull(i), 24.dp, enabled = i < state.heroCardIds.size, hero = true) { onHeroCardTap(i) }
                Text("|${state.heroPosition ?: "—"}", color = CakeColors.TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, fontFamily = Mono)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until 5) {
                    val unlocked = i < state.boardIds.size || (state.phase == com.solverin.app.presentation.StreetFlowPhase.DEAL_BOARD_CARDS && i < state.boardIds.size + state.cardsNeeded)
                    if (i < state.boardIds.size) CardSlot(state.boardIds[i], 24.dp, enabled = true) { onBoardCardTap(i) }
                    else LockedSlot(unlocked, 24.dp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Pot ${bb(state.potBb)} · SPR ${spr(state.spr)}", color = CakeColors.Gold, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1)
            val live = state.seats.filter {
                state.heroPosition != null && it.inHand && (it.isHero || it.investBb > 0 || it.actedThisStreet || state.street != TableStreet.PREFLOP)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.End)) {
                live.forEach { LiveChip(it) { onSeatTap(it.position) } }
            }
        }
    }
}

@Composable
private fun StreetPill(street: TableStreet, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(CakeColors.Cyan),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(street.name, color = CakeColors.Ink, fontWeight = FontWeight.Black, fontSize = 10.sp, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (s in 0 until 4) Box(
                Modifier.width(8.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (s <= street.ordinal) CakeColors.Ink else CakeColors.Ink.copy(alpha = 0.25f)),
            )
        }
    }
}

@Composable
private fun LiveChip(seat: SeatView, onClick: () -> Unit) {
    val c = when {
        seat.isToAct -> CakeColors.Cyan
        seat.isHero -> CakeColors.Purple
        seat.isSelected -> CakeColors.Gold
        else -> CakeColors.CardBorder
    }
    Text(
        "${seat.label}${if (seat.isHero) "★" else ""} ${bb(seat.investBb)}/${bb(seat.stackBb)}",
        color = CakeColors.TextPrimary, fontFamily = Mono, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Clip,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(CakeColors.BackgroundAlt).border(1.dp, c, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun LockedSlot(unlocked: Boolean, width: Dp) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier.width(width).aspectRatio(0.72f).clip(shape).background(CakeColors.Background)
            .border(1.dp, if (unlocked) CakeColors.Cyan else CakeColors.CardBorder, shape).alpha(if (unlocked) 1f else 0.6f),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (unlocked) "?" else "🔒", color = CakeColors.TextSecondary, fontSize = 9.sp)
    }
}

/** One card slot (empty = dark placeholder); four-colour suits. */
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
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(Card.RANK_CHARS[Card.rank(card)].toString(), color = CakeColors.Ink, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, lineHeight = 12.sp)
                Text(CakeColors.suitSymbols[suit], color = if (suit == 3) CakeColors.Ink else CakeColors.suitColors[suit], fontSize = 11.sp, lineHeight = 11.sp)
            }
        }
    }
}
