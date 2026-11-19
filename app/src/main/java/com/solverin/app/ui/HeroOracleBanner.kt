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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.solverin.app.presentation.ActKind
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
                    Text(when {
                        state.isComputing -> "◌ RESOLVIENDO NODO…"
                        state.oraclePreview -> "★ MAX EV · TU PRÓXIMO TURNO"
                        else -> "★ ACCIÓN MAX EV ACTIVA"
                    }, color = CakeColors.Gold,
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
            .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("👑", fontSize = 18.sp)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(o.shortName, color = CakeColors.Ink, fontWeight = FontWeight.Black, fontSize = actionFontSize(o.shortName),
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
            // Implicit EV (blinds won by the action) kept small and muted: the amount to put in is the badge on the right.
            Text("${o.implicitEvLabel} · Nash ${pct(o.frequency)}", color = CakeColors.Ink.copy(alpha = 0.58f), fontWeight = FontWeight.Normal,
                fontSize = 10.sp, fontFamily = Mono, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            val badge = when {
                state.heroFoldPending -> "✓ FOLD ASUMIDO · introduce otra acción para seguir"
                state.manualDeviation -> "⚠ DESVÍO MANUAL ACTIVO: ${state.committedAction?.shortName ?: ""} (${signedBb(state.committedAction?.deltaEvBb ?: 0.0)})"
                state.heroCommittedKey != null -> "✓ ASUMIDA AUTOMÁTICAMENTE"
                else -> "PRÓXIMO TURNO · el rival habla antes"
            }
            Text(badge, color = CakeColors.Ink, fontWeight = FontWeight.Bold, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("👆 Mantén presionado para ver por qué", color = CakeColors.Ink.copy(alpha = 0.7f), fontSize = 8.sp, maxLines = 1)
        }
        Spacer(Modifier.width(6.dp))
        HeroBetAmountBadge(o, Modifier.wrapContentWidth())
    }
}

private fun actionFontSize(name: String) = when {
    name.length <= 5 -> 22.sp
    name.length <= 7 -> 19.sp
    else -> 16.sp
}

/** Never-truncated amount pill: street total for bets / raises (+ % pot), chips to put in for a call, 0.0 for check / fold. */
@Composable
fun HeroBetAmountBadge(o: ActionOption, modifier: Modifier = Modifier, compact: Boolean = false) {
    val aggressive = o.kind == ActKind.BET || o.kind == ActKind.RAISE || o.kind == ActKind.ALL_IN
    val passivePay = o.kind == ActKind.CALL || o.kind == ActKind.LIMP
    val border = if (aggressive) CakeColors.Gold else CakeColors.Cyan
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CakeColors.Background)
            .border(2.dp, border, RoundedCornerShape(12.dp))
            .padding(horizontal = if (compact) 5.dp else 8.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (passivePay) Text("PAGAR", color = CakeColors.Cyan, fontWeight = FontWeight.Black, fontSize = if (compact) 8.sp else 9.sp, maxLines = 1, softWrap = false)
        Text("${amount(o.amountBb)} BB", color = CakeColors.TextPrimary, fontFamily = Mono, fontWeight = FontWeight.Black,
            fontSize = if (compact) 12.sp else 20.sp, maxLines = 1, softWrap = false)
        if (aggressive) Text("${"%.0f".format(o.potPct * 100)}% Pot", color = CakeColors.Gold, fontFamily = Mono, fontWeight = FontWeight.Bold,
            fontSize = if (compact) 8.sp else 10.sp, maxLines = 1, softWrap = false)
    }
}

private fun amount(x: Double) = "%.1f".format(java.util.Locale.US, x)

@Composable
private fun OptionChip(o: ActionOption, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = CakeColors.action(o.kind)
    Row(
        modifier
            .alpha(if (enabled || selected) 1f else 0.8f)
            .clip(CakeShapes.Chip)
            .background(if (selected) c.copy(alpha = 0.28f) else CakeColors.BackgroundAlt)
            .border(1.5.dp, if (selected) c else CakeColors.CardBorder, CakeShapes.Chip)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(o.shortName, color = c, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
            Text("${pct(o.frequency)} · ${o.implicitEvLabel}", color = CakeColors.TextSecondary.copy(alpha = 0.8f), fontWeight = FontWeight.Normal,
                fontSize = 8.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("${amount(o.amountBb)}bb", color = CakeColors.TextPrimary, fontSize = 10.sp, fontFamily = Mono, fontWeight = FontWeight.Black,
            maxLines = 1, softWrap = false, modifier = Modifier.wrapContentWidth())
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
            Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                state.options.forEach { o ->
                    val c = if (o.isMaxEv) CakeColors.Gold else CakeColors.action(o.kind)
                    CakeButtonBox(c, Modifier.weight(1f).fillMaxHeight(), onClick = { onPick(o.key) }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(o.shortName, color = CakeColors.Ink, fontWeight = FontWeight.Black, fontSize = 11.sp, maxLines = 1, softWrap = false)
                            HeroBetAmountBadge(o, compact = true)
                            Text("${o.implicitEvLabel} · ${pct(o.frequency)}", color = CakeColors.Ink.copy(alpha = 0.6f), fontWeight = FontWeight.Normal,
                                fontSize = 8.sp, fontFamily = Mono, maxLines = 1)
                            Text("Δ ${signedBb(o.deltaEvBb)}", color = CakeColors.Ink, fontWeight = FontWeight.Bold, fontSize = 8.sp, fontFamily = Mono, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
