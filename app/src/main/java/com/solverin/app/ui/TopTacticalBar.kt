package com.solverin.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solverin.app.presentation.HandViewState
import com.solverin.app.presentation.SolverViewModel
import com.solverin.app.presentation.VillainProfilePreset
import com.solverin.engine.core.Stake

/** Which compact modal of the tactical layer is open. */
enum class TacticalOverlay { NONE, STAKE, STACK, PROFILE, DEVIATION }

/** Top tactical bar: 💰 Stake · 🥞 Stack · 🎯 Perfil rival · ↩ Undo · 🔄 Nueva mano. */
@Composable
fun TopTacticalBar(
    state: HandViewState,
    onOpen: (TacticalOverlay) -> Unit,
    onUndo: () -> Unit,
    onNewHand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Chip("💰 Stake", state.stake.name, CakeColors.Cyan, Modifier.weight(1f)) { onOpen(TacticalOverlay.STAKE) }
        Chip("🥞 Stack" + (state.selectedSeat?.let { " $it" } ?: ""),
            "${bb(state.selectedSeat?.let { s -> state.seats.firstOrNull { it.label == s }?.let { it.stackBb + it.investBb } } ?: state.effectiveStackBb)}bb",
            CakeColors.Gold, Modifier.weight(1f)) { onOpen(TacticalOverlay.STACK) }
        Chip("🎯 Rival", state.profile.label, CakeColors.Purple, Modifier.weight(1.35f)) { onOpen(TacticalOverlay.PROFILE) }
        CakeButton("↩", CakeColors.Purple, Modifier.weight(0.55f).fillMaxHeight(0.86f), enabled = state.canUndo,
            textColor = CakeColors.TextPrimary, fontSize = 18.sp, onClick = onUndo)
        CakeButton("🔄", CakeColors.Pink, Modifier.weight(0.55f).fillMaxHeight(0.86f), textColor = CakeColors.TextPrimary,
            fontSize = 16.sp, onClick = onNewHand)
    }
}

@Composable
private fun Chip(caption: String, value: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.fillMaxHeight(0.86f).clip(CakeShapes.Button).background(CakeColors.Card)
            .border(1.5.dp, CakeColors.CardBorder, CakeShapes.Button).clickable(onClick = onClick).padding(horizontal = 5.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(caption, style = LabelStyle.copy(fontSize = 9.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, color = color, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Floating stake row: NL2 … NL200 (rake % and cap shown). */
@Composable
fun StakeOverlay(current: Stake, onPick: (Stake) -> Unit, modifier: Modifier = Modifier) {
    CakePanel(modifier) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("💰 STAKE · rake y cap", color = CakeColors.Cyan, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth().height(58.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Stake.entries.forEach { s ->
                    val sel = s == current
                    CakeButton("${s.name}\n${pctPlain(s.defaultRake.rate)}·${bb(s.defaultRake.capBb)}", if (sel) CakeColors.Cyan else CakeColors.BackgroundAlt,
                        Modifier.weight(1f).fillMaxHeight(), textColor = if (sel) CakeColors.Ink else CakeColors.TextPrimary, fontSize = 10.sp,
                        fontFamily = Mono) { onPick(s) }
                }
            }
        }
    }
}

/** Stack selector: presets, ±5 / ±1 bb; edits the selected seat (tap a seat first) or the whole table. */
@Composable
fun StackOverlay(state: HandViewState, onSet: (Double) -> Unit, onAdjust: (Double) -> Unit, onClearSeat: () -> Unit, modifier: Modifier = Modifier) {
    CakePanel(modifier) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🥞 STACK " + (state.selectedSeat?.let { "ASIENTO $it" } ?: "MESA ENTERA"), color = CakeColors.Gold,
                    fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("SPR ${spr(state.spr)} · Eff ${bb(state.effectiveStackBb)}bb", color = CakeColors.TextSecondary, fontFamily = Mono, fontSize = 11.sp)
            }
            for (row in SolverViewModel.STACK_PRESETS.chunked(5)) {
                Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { v ->
                        CakeButton("${bb(v)}bb", CakeColors.BackgroundAlt, Modifier.weight(1f).fillMaxHeight(), textColor = CakeColors.TextPrimary,
                            fontSize = 12.sp, fontFamily = Mono) { onSet(v) }
                    }
                    if (row.size < 5) androidx.compose.foundation.layout.Spacer(Modifier.weight((5 - row.size).toFloat()))
                }
            }
            Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(-5.0, -1.0, 1.0, 5.0).forEach { d ->
                    CakeButton(if (d > 0) "+${bb(d)}bb" else "${bb(d)}bb", if (d > 0) CakeColors.Mint else CakeColors.Pink,
                        Modifier.weight(1f).fillMaxHeight(), fontSize = 13.sp, fontFamily = Mono) { onAdjust(d) }
                }
                CakeButton("MESA", CakeColors.Purple, Modifier.weight(1f).fillMaxHeight(), enabled = state.selectedSeat != null,
                    textColor = CakeColors.TextPrimary, fontSize = 12.sp, onClick = onClearSeat)
            }
            Text("Toca un asiento de la mesa para ajustar solo su stack.", color = CakeColors.TextSecondary, fontSize = 10.sp)
        }
    }
}

/** Villain node-lock profile picker. */
@Composable
fun ProfileOverlay(current: VillainProfilePreset, onPick: (VillainProfilePreset) -> Unit, modifier: Modifier = Modifier) {
    CakePanel(modifier) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("🎯 PERFIL RIVAL · NODE LOCK", color = CakeColors.Purple, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                VillainProfilePreset.entries.forEach { p ->
                    val sel = p == current
                    CakeButton(p.label, if (sel) CakeColors.Purple else CakeColors.BackgroundAlt, Modifier.weight(1f).fillMaxHeight(),
                        textColor = CakeColors.TextPrimary, fontSize = 11.sp) { onPick(p) }
                }
            }
        }
    }
}

private fun pctPlain(x: Double) = "%.0f%%".format(x * 100)

fun spr(x: Double): String = if (x.isInfinite() || x.isNaN()) "∞" else "%.1f".format(x)
