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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solverin.app.presentation.ActionOption
import com.solverin.app.presentation.HandViewState

/** Hero Oracle: the Max-EV action (auto-active) with exact EV and Nash frequency, plus override chips. */
@Composable
fun HeroOracleBanner(state: HandViewState, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    CakePanel(modifier) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val best = state.maxEvAction
            val committed = state.options.firstOrNull { it.key == state.heroCommittedKey }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        state.isComputing -> "◌ SOLVING…"
                        committed != null && committed.key != best?.key -> "✎ HERO OVERRIDE"
                        committed != null -> "★ MAX EV AUTO-ACTIVE"
                        best != null -> "★ MAX EV · NEXT NODE"
                        else -> "★ ORACLE"
                    },
                    color = if (committed != null && committed.key != best?.key) CakeColors.Purple else CakeColors.Gold,
                    fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    state.nodeLabel + if (state.approximate) " ≈" else "", color = CakeColors.TextSecondary, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (state.latencyMs > 0) Text("${"%.0f".format(state.latencyMs)}ms", color = CakeColors.TextSecondary, fontSize = 10.sp, fontFamily = Mono)
            }
            if (best == null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.isComputing) "Solving hero node…" else "Pick hole cards and seat to start the oracle",
                        color = CakeColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                MaxEvCard(best, selected = state.heroCommittedKey == best.key, enabled = state.heroOverridable,
                    onClick = { onSelect(best.key) }, modifier = Modifier.fillMaxWidth().weight(1.15f))
                val others = state.options.filter { it.key != best.key }
                if (others.isNotEmpty()) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
private fun MaxEvCard(o: ActionOption, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .clip(CakeShapes.Button)
            .background(CakeColors.MaxEvGradient)
            .then(if (selected) Modifier.border(2.dp, CakeColors.TextPrimary, CakeShapes.Button) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("👑", fontSize = 22.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(o.label.uppercase(), color = CakeColors.Ink, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Nash: ${pct(o.frequency)}", color = CakeColors.Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = Mono)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("EV", color = CakeColors.Ink, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            Text(signedBb(o.evBb), color = CakeColors.Ink, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, fontFamily = Mono)
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
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(o.label, color = c, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${pct(o.frequency)} · ${signedBb(o.evBb)}", color = CakeColors.TextSecondary, fontSize = 10.sp, fontFamily = Mono, maxLines = 1)
    }
}
