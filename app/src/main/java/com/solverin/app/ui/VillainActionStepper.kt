package com.solverin.app.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solverin.app.presentation.ActKind
import com.solverin.app.presentation.HandViewState
import com.solverin.app.presentation.TableStreet
import com.solverin.app.presentation.VillainOption

/** Seat-by-seat villain input: passive row, sizing presets, custom ± size, All-In and Undo. */
@Composable
fun VillainActionStepper(
    state: HandViewState,
    onAction: (VillainOption) -> Unit,
    onCustomStep: (Int) -> Unit,
    onCustomExecute: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CakePanel(modifier) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (state.heroToAct) "HERO (${state.toAct}) · SOLVING…" else "${state.toAct ?: "—"} TO ACT",
                        color = CakeColors.Cyan, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                    )
                    Text(
                        state.history.takeLast(3).joinToString("  ·  ").ifEmpty { "${state.street.name} · blinds posted" },
                        color = CakeColors.TextSecondary, fontSize = 10.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                CakeButton("↶ Undo", CakeColors.Purple, Modifier.width(84.dp).fillMaxHeight(), enabled = state.canUndo,
                    textColor = CakeColors.TextPrimary, fontSize = 12.sp, onClick = onUndo)
            }
            if (state.heroToAct || state.villainOptions.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("Solving hero's Max-EV action…", color = CakeColors.TextSecondary, fontWeight = FontWeight.Bold)
                }
                return@Column
            }
            val passive = state.villainOptions.filter { it.kind == ActKind.FOLD || it.kind == ActKind.CHECK || it.kind == ActKind.CALL || it.kind == ActKind.LIMP }
            val allIn = state.villainOptions.firstOrNull { it.kind == ActKind.ALL_IN }
            val presets = state.villainOptions.filter { it.kind == ActKind.BET || it.kind == ActKind.RAISE }
            Row(Modifier.fillMaxWidth().weight(1.25f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                passive.forEach { o ->
                    CakeButton(o.label.uppercase(), CakeColors.action(o.kind), Modifier.weight(1f).fillMaxHeight(), fontSize = 15.sp) { onAction(o) }
                }
            }
            val rows = presets.chunked(4)
            val rowSlots = 2
            for (i in 0 until rowSlots) {
                val row = rows.getOrNull(i).orEmpty()
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { o ->
                        CakeButton(o.label, if (o.kind == ActKind.BET) CakeColors.Mint else CakeColors.Purple, Modifier.weight(1f).fillMaxHeight(),
                            textColor = if (o.kind == ActKind.BET) CakeColors.Ink else CakeColors.TextPrimary, fontSize = 12.sp, fontFamily = Mono) { onAction(o) }
                    }
                    if (row.size < 4) Spacer(Modifier.weight((4 - row.size).toFloat()))
                }
            }
            if (rows.size > rowSlots) {
                Text("+${rows.drop(rowSlots).sumOf { it.size }} sizes via custom", color = CakeColors.TextSecondary, fontSize = 10.sp)
            }
            Row(Modifier.fillMaxWidth().weight(1.1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CakeButton("−", CakeColors.BackgroundAlt, Modifier.weight(0.7f).fillMaxHeight(), enabled = state.customAmountBb > state.minCustomBb + 1e-9,
                    textColor = CakeColors.TextPrimary, fontSize = 20.sp) { onCustomStep(-1) }
                Box(Modifier.weight(1.3f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text("${bb(state.customAmountBb)}bb", color = CakeColors.TextPrimary, fontFamily = Mono, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                }
                CakeButton("+", CakeColors.BackgroundAlt, Modifier.weight(0.7f).fillMaxHeight(), enabled = state.customAmountBb < state.maxCustomBb - 1e-9,
                    textColor = CakeColors.TextPrimary, fontSize = 20.sp) { onCustomStep(1) }
                val betWord = if (state.street == TableStreet.PREFLOP || state.villainOptions.any { it.kind == ActKind.CALL || it.kind == ActKind.LIMP }) "RAISE" else "BET"
                CakeButton("$betWord ${bb(state.customAmountBb)}", CakeColors.Mint, Modifier.weight(1.6f).fillMaxHeight(), fontSize = 13.sp,
                    fontFamily = Mono, onClick = onCustomExecute)
                if (allIn != null) CakeButton(allIn.label.uppercase(), CakeColors.Pink, Modifier.weight(1.6f).fillMaxHeight(),
                    textColor = CakeColors.TextPrimary, fontSize = 12.sp) { onAction(allIn) }
            }
        }
    }
}
