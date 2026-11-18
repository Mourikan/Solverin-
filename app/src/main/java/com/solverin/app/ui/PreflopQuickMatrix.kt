package com.solverin.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solverin.app.presentation.ActKind
import com.solverin.app.presentation.HandViewState
import com.solverin.app.presentation.SeatView
import com.solverin.app.presentation.StreetFlowPhase
import com.solverin.app.presentation.VillainOption
import com.solverin.engine.core.Position

private val SeatShape = RoundedCornerShape(14.dp)

/**
 * Preflop quick matrix: the 6 seats at once. Picking hero's seat (position phase), then assigning villain actions on
 * any seat; seats without an assigned action fold by omission. "✓ Avanzar al Flop" closes the round folding the rest.
 */
@Composable
fun PreflopQuickMatrix(
    state: HandViewState,
    onPickHero: (Position) -> Unit,
    onSeat: (Position) -> Unit,
    onAction: (VillainOption) -> Unit,
    onCustomStep: (Int) -> Unit,
    onCustomExecute: () -> Unit,
    onAdvance: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val picking = state.phase == StreetFlowPhase.PICK_HERO_POSITION
    CakePanel(modifier) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        picking -> "ELIGE TU POSICIÓN · ${state.heroCards.joinToString(" ")}"
                        state.heroFoldPending -> "HÉROE: FOLD (MAX EV) · toca un rival para seguir"
                        state.isComputing -> "RESOLVIENDO TU ACCIÓN MAX EV…"
                        state.actingSeat != null -> "ACCIÓN DE ${state.actingSeat} · resto = FOLD por omisión"
                        else -> "PREFLOP · MATRIZ RÁPIDA"
                    },
                    color = if (state.heroFoldPending) CakeColors.Pink else CakeColors.Cyan, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                CakeButton("↩ Undo", CakeColors.Purple, Modifier.width(80.dp).fillMaxHeight(), enabled = state.canUndo,
                    textColor = CakeColors.TextPrimary, fontSize = 12.sp, onClick = onUndo)
            }
            val seats = if (state.seats.isNotEmpty()) state.seats else emptyList()
            for (row in Position.entries.chunked(3)) {
                Row(Modifier.fillMaxWidth().weight(if (picking) 2f else 1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { p ->
                        val seat = seats.firstOrNull { it.position == p }
                        if (picking) CakeButton(p.short, if (p.isBlind) CakeColors.Purple else CakeColors.Cyan, Modifier.weight(1f).fillMaxHeight(),
                            textColor = if (p.isBlind) CakeColors.TextPrimary else CakeColors.Ink, fontSize = 26.sp) { onPickHero(p) }
                        else if (seat != null) SeatCard(state, seat, Modifier.weight(1f).fillMaxHeight()) { onSeat(p) }
                    }
                }
            }
            if (picking) return@Column
            ActionBar(state, onAction, onCustomStep, onCustomExecute, Modifier.fillMaxWidth().weight(2.4f))
            CakeButton("✓ Avanzar al Flop (Resto Fold)", CakeColors.Cyan, Modifier.fillMaxWidth().weight(0.8f), enabled = state.canAdvancePreflop,
                fontSize = 15.sp, onClick = onAdvance)
        }
    }
}

@Composable
private fun SeatCard(state: HandViewState, seat: SeatView, modifier: Modifier, onClick: () -> Unit) {
    // No action assigned on this street: the seat folds by omission unless the user assigns one.
    val implicit = seat.inHand && !seat.isHero && !seat.actedThisStreet && !seat.isMatrixSelected
    val (title, color) = when {
        seat.isHero -> "★ TÚ (Auto: ${state.committedAction?.label ?: state.maxEvAction?.label ?: "…"})" to CakeColors.Gold
        !seat.inHand -> "FOLD" to CakeColors.Pink
        seat.isMatrixSelected -> "← ASIGNA ACCIÓN" to CakeColors.Cyan
        implicit -> "FOLD (implícito)" to CakeColors.TextSecondary
        else -> seat.status.ifEmpty { "—" } to CakeColors.Mint
    }
    val border = when {
        seat.isMatrixSelected -> CakeColors.Cyan
        seat.isHero -> CakeColors.Gold
        else -> CakeColors.CardBorder
    }
    Column(
        modifier
            .alpha(if (implicit || !seat.inHand) 0.55f else 1f)
            .clip(SeatShape)
            .background(if (seat.isHero) CakeColors.Purple.copy(alpha = 0.3f) else CakeColors.BackgroundAlt)
            .border(if (seat.isMatrixSelected) 2.5.dp else 1.5.dp, border, SeatShape)
            .clickable(enabled = seat.canAct || state.heroFoldPending, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(seat.label, color = CakeColors.TextPrimary, fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 1)
        Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 2, textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis, lineHeight = 11.sp)
        Text("${bb(seat.investBb)} / ${bb(seat.stackBb)}bb", color = CakeColors.TextSecondary, fontSize = 9.sp, fontFamily = Mono, maxLines = 1)
    }
}

@Composable
private fun ActionBar(
    state: HandViewState, onAction: (VillainOption) -> Unit, onCustomStep: (Int) -> Unit, onCustomExecute: () -> Unit, modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (state.villainOptions.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f).clip(CakeShapes.Button).background(CakeColors.BackgroundAlt), contentAlignment = Alignment.Center) {
                Text(if (state.isComputing) "Resolviendo tu acción Max EV…" else "Toca un asiento rival para asignar su acción",
                    color = CakeColors.TextSecondary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            return@Column
        }
        val passive = state.villainOptions.filter { it.kind == ActKind.FOLD || it.kind == ActKind.CHECK || it.kind == ActKind.CALL || it.kind == ActKind.LIMP }
        val raises = state.villainOptions.filter { it.kind == ActKind.BET || it.kind == ActKind.RAISE }.take(4)
        val allIn = state.villainOptions.firstOrNull { it.kind == ActKind.ALL_IN }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            passive.forEach { o -> CakeButton(o.label.uppercase(), CakeColors.action(o.kind), Modifier.weight(1f).fillMaxHeight(), fontSize = 13.sp) { onAction(o) } }
            if (allIn != null) CakeButton("ALL-IN", CakeColors.Mint, Modifier.weight(1f).fillMaxHeight(), fontSize = 13.sp) { onAction(allIn) }
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            raises.forEach { o -> CakeButton(o.label, CakeColors.Mint, Modifier.weight(1f).fillMaxHeight(), fontSize = 11.sp, fontFamily = Mono) { onAction(o) } }
            if (raises.size < 4) Spacer(Modifier.weight((4 - raises.size).toFloat()))
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            CakeButton("−0.5", CakeColors.BackgroundAlt, Modifier.weight(0.8f).fillMaxHeight(), enabled = state.customAmountBb > state.minCustomBb + 1e-9,
                textColor = CakeColors.TextPrimary, fontSize = 13.sp, fontFamily = Mono) { onCustomStep(-1) }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text("${bb(state.customAmountBb)}bb", color = CakeColors.TextPrimary, fontFamily = Mono, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            }
            CakeButton("+0.5", CakeColors.BackgroundAlt, Modifier.weight(0.8f).fillMaxHeight(), enabled = state.customAmountBb < state.maxCustomBb - 1e-9,
                textColor = CakeColors.TextPrimary, fontSize = 13.sp, fontFamily = Mono) { onCustomStep(1) }
            CakeButton("RAISE ${bb(state.customAmountBb)}", CakeColors.Mint, Modifier.weight(1.6f).fillMaxHeight(), fontSize = 13.sp, fontFamily = Mono,
                onClick = onCustomExecute)
        }
    }
}
