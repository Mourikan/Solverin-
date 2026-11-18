package com.solverin.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solverin.app.presentation.ActionOption
import com.solverin.app.presentation.HandViewState
import com.solverin.app.presentation.StrategyExplanation

/**
 * Hero oracle (always visible): the Max-EV action assumed automatically with exact EV and Nash frequency,
 * hold-to-explain gesture ([onHold] true while pressed) and the "⚡ Hice otra jugada" deviation button.
 */
@Composable
fun HeroOracleBanner(
    state: HandViewState,
    onHold: (Boolean) -> Unit,
    onDeviation: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    CakePanel(modifier) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val best = state.maxEvAction
            Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (state.isComputing) "◌ RESOLVIENDO NODO…" else "★ ACCIÓN MAX EV ACTIVA", color = CakeColors.Gold,
                        fontWeight = FontWeight.Black, fontSize = 12.sp, maxLines = 1)
                    Text(
                        state.nodeLabel + (if (state.approximate) " ≈" else "") + if (state.latencyMs > 0) "  ${"%.0f".format(state.latencyMs)}ms" else "",
                        color = CakeColors.TextSecondary, fontSize = 10.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                CakeButton("⚡ Hice otra jugada", CakeColors.Purple, Modifier.width(132.dp).fillMaxHeight(), enabled = state.heroOverridable,
                    textColor = CakeColors.TextPrimary, fontSize = 11.sp, onClick = onDeviation)
            }
            if (best == null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(if (state.isComputing) "Resolviendo el nodo del héroe…" else "Elige tus 2 cartas y tu posición para activar el oráculo",
                        color = CakeColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                MaxEvCard(state, best, onHold, Modifier.fillMaxWidth().weight(1.35f))
                val others = state.options.filter { it.key != best.key }
                if (others.isNotEmpty()) Row(Modifier.fillMaxWidth().weight(0.8f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    others.forEach { o ->
                        OptionChip(o, selected = state.heroCommittedKey == o.key, enabled = state.heroOverridable,
                            onClick = { onSelect(o.key) }, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun MaxEvCard(state: HandViewState, o: ActionOption, onHold: (Boolean) -> Unit, modifier: Modifier) {
    val hold by rememberUpdatedState(onHold)
    Row(
        modifier
            .clip(CakeShapes.Button)
            .background(CakeColors.MaxEvGradient)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    hold(true)
                    try { tryAwaitRelease() } finally { hold(false) }
                })
            }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("👑", fontSize = 22.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(o.label.uppercase(), color = CakeColors.Ink, fontWeight = FontWeight.Black, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val badge = when {
                state.heroFoldPending -> "✓ FOLD ASUMIDO · introduce otra acción para seguir"
                state.manualDeviation -> "⚠ DESVÍO MANUAL ACTIVO: ${state.committedAction?.label ?: ""} (${signedBb(state.committedAction?.deltaEvBb ?: 0.0)})"
                state.heroCommittedKey != null -> "✓ ASUMIDA AUTOMÁTICAMENTE"
                else -> "PRÓXIMO NODO DEL HÉROE"
            }
            Text(badge, color = CakeColors.Ink, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("👆 Mantén presionado para ver por qué", color = CakeColors.Ink.copy(alpha = 0.7f), fontSize = 9.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(signedBb(o.evBb), color = CakeColors.Ink, fontWeight = FontWeight.Black, fontSize = 18.sp, fontFamily = Mono)
            Text("Nash: ${pct(o.frequency)}", color = CakeColors.Ink, fontWeight = FontWeight.Bold, fontSize = 11.sp, fontFamily = Mono)
            Text("SPR ${spr(state.spr)}", color = CakeColors.Ink, fontSize = 10.sp, fontFamily = Mono)
        }
    }
}

@Composable
private fun OptionChip(o: ActionOption, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = CakeColors.action(o.kind)
    Column(
        modifier
            .alpha(if (enabled || selected) 1f else 0.8f)
            .clip(CakeShapes.Chip)
            .background(if (selected) c.copy(alpha = 0.28f) else CakeColors.BackgroundAlt)
            .border(1.5.dp, if (selected) c else CakeColors.CardBorder, CakeShapes.Chip)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(o.label, color = c, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${pct(o.frequency)} · ${signedBb(o.evBb)}", color = CakeColors.TextSecondary, fontSize = 9.sp, fontFamily = Mono, maxLines = 1)
    }
}

/** Hold-to-explain overlay (visible only while the Max-EV card is pressed). */
@Composable
fun ExplainOverlay(e: StrategyExplanation, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(CakeShapes.Panel).background(CakeColors.BackgroundAlt).border(2.dp, CakeColors.Gold, CakeShapes.Panel).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(e.title, color = CakeColors.Gold, fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        e.points.forEach {
            Text(it, color = CakeColors.TextPrimary, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Hice otra jugada": every legal hero action with EV, Nash and ΔEV vs the optimum. */
@Composable
fun DeviationOverlay(state: HandViewState, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    CakePanel(modifier) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("⚡ ¿QUÉ HICISTE EN LA MESA?", color = CakeColors.Purple, fontWeight = FontWeight.Black, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                state.options.forEach { o ->
                    val c = if (o.isMaxEv) CakeColors.Gold else CakeColors.action(o.kind)
                    val text = "${o.label}\n${signedBb(o.evBb)}\nNash ${pct(o.frequency)}\nΔ ${signedBb(o.deltaEvBb)}"
                    CakeButton(text, c, Modifier.weight(1f).fillMaxHeight(), textColor = CakeColors.Ink, fontSize = 10.sp, fontFamily = Mono, maxLines = 4) { onPick(o.key) }
                }
            }
        }
    }
}
