package com.solverin.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solverin.app.presentation.HandViewState
import com.solverin.app.presentation.SolverViewModel
import com.solverin.app.presentation.StreetFlowPhase
import com.solverin.app.presentation.VillainProfilePreset
import com.solverin.engine.core.Position
import com.solverin.engine.core.Stake

/** Zero-scroll portrait screen: top bar 8% · oracle 24% · table 20% · input zone 48%. */
@Composable
fun SolverScreen(vm: SolverViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().background(CakeColors.ScreenGradient).safeDrawingPadding().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TopBar(
            state,
            onStake = { vm.setStake(Stake.entries[(state.stake.ordinal + 1) % Stake.entries.size]) },
            onProfile = { vm.setProfile(VillainProfilePreset.entries[(state.profile.ordinal + 1) % VillainProfilePreset.entries.size]) },
            onReset = vm::resetHand,
            modifier = Modifier.fillMaxWidth().weight(0.08f),
        )
        HeroOracleBanner(state, vm::selectHeroAction, Modifier.fillMaxWidth().weight(0.24f))
        CompactTable6Max(state, vm::editHeroCard, vm::editBoardCard, Modifier.fillMaxWidth().weight(0.20f))
        Box(Modifier.fillMaxWidth().weight(0.48f)) {
            AnimatedContent(
                targetState = state.phase,
                transitionSpec = { (fadeIn() + slideInHorizontally { it / 6 }) togetherWith fadeOut() },
                label = "zone3",
                modifier = Modifier.fillMaxSize(),
            ) { phase ->
                val z = Modifier.fillMaxSize()
                when (phase) {
                    StreetFlowPhase.PICK_HERO_CARDS, StreetFlowPhase.DEAL_BOARD_CARDS ->
                        TwoTapCardPicker(state, vm::pickRank, vm::pickSuit, vm::undo, z)
                    StreetFlowPhase.PICK_HERO_POSITION -> PositionPicker(state, vm::setHeroPosition, vm::undo, z)
                    StreetFlowPhase.VILLAIN_ACTION_STEPPER ->
                        VillainActionStepper(state, vm::villainAct, vm::adjustCustom, vm::villainCustom, vm::undo, z)
                    StreetFlowPhase.HAND_COMPLETE -> HandCompletePanel(state, vm::resetHand, vm::undo, z)
                }
            }
            state.error?.let {
                Text(
                    it, color = CakeColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp).clip(CakeShapes.Chip).background(CakeColors.Pink.copy(alpha = 0.85f))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun TopBar(state: HandViewState, onStake: () -> Unit, onProfile: () -> Unit, onReset: () -> Unit, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(state.stake.name, "STAKE", CakeColors.Cyan, Modifier.weight(0.9f), onStake)
        Pill(state.profile.label.uppercase(), "VILLAIN", CakeColors.Purple, Modifier.weight(1.3f), onProfile)
        Column(Modifier.weight(1.2f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Pot: ${bb(state.potBb)} bb", color = CakeColors.Gold, fontFamily = Mono, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, maxLines = 1)
            Text("Eff: ${bb(state.effectiveStackBb)} bb", color = CakeColors.TextSecondary, fontFamily = Mono, fontSize = 11.sp, maxLines = 1)
        }
        CakeButton("RESET", CakeColors.Pink, Modifier.weight(0.8f).fillMaxHeight(0.85f), textColor = CakeColors.TextPrimary, fontSize = 12.sp, onClick = onReset)
    }
}

@Composable
private fun Pill(value: String, caption: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.fillMaxHeight(0.85f).clip(CakeShapes.Button).background(CakeColors.Card).clickable(onClick = onClick).padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(caption, style = LabelStyle.copy(fontSize = 8.sp))
        Text("$value ▾", color = color, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PositionPicker(state: HandViewState, onPick: (Position) -> Unit, onUndo: () -> Unit, modifier: Modifier) {
    CakePanel(modifier) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("HERO POSITION", color = CakeColors.Cyan, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(state.heroCards.joinToString(" "), color = CakeColors.TextPrimary, fontFamily = Mono, fontWeight = FontWeight.Bold)
                CakeButton("↶ Undo", CakeColors.Purple, Modifier.padding(start = 8.dp).width(84.dp).fillMaxHeight(), enabled = state.canUndo,
                    textColor = CakeColors.TextPrimary, fontSize = 12.sp, onClick = onUndo)
            }
            for (row in Position.entries.chunked(3)) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { p ->
                        CakeButton(p.short, if (p.isBlind) CakeColors.Purple else CakeColors.Cyan, Modifier.weight(1f).fillMaxHeight(),
                            textColor = if (p.isBlind) CakeColors.TextPrimary else CakeColors.Ink, fontSize = 26.sp) { onPick(p) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HandCompletePanel(state: HandViewState, onNew: () -> Unit, onUndo: () -> Unit, modifier: Modifier) {
    CakePanel(modifier) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val heroIn = state.seats.firstOrNull { it.isHero }?.inHand == true
            val alive = state.seats.count { it.inHand }
            Text(
                when {
                    !heroIn -> "HAND OVER · HERO FOLDED"
                    alive == 1 -> "HAND OVER · HERO WINS ${bb(state.potBb)}bb"
                    else -> "SHOWDOWN · POT ${bb(state.potBb)}bb"
                },
                color = CakeColors.Gold, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp,
            )
            Column(Modifier.fillMaxWidth().weight(1f)) {
                state.history.takeLast(8).forEach {
                    Text(it, color = CakeColors.TextSecondary, fontFamily = Mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CakeButton("↶ UNDO", CakeColors.Purple, Modifier.weight(1f).fillMaxHeight(), enabled = state.canUndo,
                    textColor = CakeColors.TextPrimary, onClick = onUndo)
                CakeButton("NEW HAND", CakeColors.Cyan, Modifier.weight(2f).fillMaxHeight(), fontSize = 18.sp, onClick = onNew)
            }
        }
    }
}
