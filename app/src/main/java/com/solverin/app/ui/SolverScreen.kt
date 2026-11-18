package com.solverin.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solverin.app.presentation.HandViewState
import com.solverin.app.presentation.SolverViewModel
import com.solverin.app.presentation.StreetFlowPhase
import com.solverin.engine.core.Position

private enum class Zone3 { CARDS, POSITION, STEPPER, COMPLETE }

/** Zero-scroll portrait screen: tactical bar 9% · oracle 25% · table 20% · sequential input zone 46%, modal overlays on top. */
@Composable
fun SolverScreen(vm: SolverViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var overlay by remember { mutableStateOf(TacticalOverlay.NONE) }
    var explaining by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(CakeColors.ScreenGradient).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TopTacticalBar(state, onOpen = { overlay = it }, onUndo = vm::undo, onNewHand = vm::resetHand, modifier = Modifier.fillMaxWidth().weight(0.09f))
            HeroOracleBanner(
                state, onHold = { explaining = it && state.explanation != null }, onDeviation = { overlay = TacticalOverlay.DEVIATION },
                onSelect = vm::overrideHeroAction, modifier = Modifier.fillMaxWidth().weight(0.25f),
            )
            CompactTable6Max(state, vm::editHeroCard, vm::editBoardCard, onSeatTap = { vm.selectSeat(it); overlay = TacticalOverlay.STACK },
                modifier = Modifier.fillMaxWidth().weight(0.20f))
            Box(Modifier.fillMaxWidth().weight(0.46f)) {
                val zone = when {
                    state.heroFoldPending -> Zone3.STEPPER
                    state.phase == StreetFlowPhase.PICK_HERO_CARDS || state.phase == StreetFlowPhase.DEAL_BOARD_CARDS -> Zone3.CARDS
                    state.phase == StreetFlowPhase.PICK_HERO_POSITION -> Zone3.POSITION
                    state.phase == StreetFlowPhase.VILLAIN_ACTION_STEPPER -> Zone3.STEPPER
                    else -> Zone3.COMPLETE
                }
                AnimatedContent(
                    targetState = zone,
                    transitionSpec = { (fadeIn() + slideInHorizontally { it / 6 }) togetherWith fadeOut() },
                    label = "zone3",
                    modifier = Modifier.fillMaxSize(),
                ) { z ->
                    val m = Modifier.fillMaxSize()
                    when (z) {
                        Zone3.CARDS -> TwoTapCardPicker(state, vm::pickRank, vm::pickSuit, vm::undo, m)
                        Zone3.POSITION -> PositionPicker(state, vm::setHeroPosition, vm::undo, m)
                        Zone3.STEPPER -> VillainActionStepper(state, vm::villainAct, vm::adjustCustom, vm::villainCustom, vm::undo,
                            onContinue = { vm.continueInHand() }, modifier = m)
                        Zone3.COMPLETE -> HandCompletePanel(state, vm::resetHand, vm::undo, m)
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
        if (overlay != TacticalOverlay.NONE) {
            Box(
                Modifier.fillMaxSize().background(Color(0xCC08060B))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { overlay = TacticalOverlay.NONE },
                contentAlignment = Alignment.TopCenter,
            ) {
                val m = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 64.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                when (overlay) {
                    TacticalOverlay.STAKE -> StakeOverlay(state.stake, { vm.setStake(it); overlay = TacticalOverlay.NONE }, m)
                    TacticalOverlay.STACK -> StackOverlay(state, vm::setEffectiveStack, vm::adjustStack, { vm.selectSeat(null) }, m)
                    TacticalOverlay.PROFILE -> ProfileOverlay(state.profile, { vm.setProfile(it); overlay = TacticalOverlay.NONE }, m)
                    TacticalOverlay.DEVIATION -> DeviationOverlay(state, { vm.overrideHeroAction(it); overlay = TacticalOverlay.NONE }, m)
                    TacticalOverlay.NONE -> {}
                }
            }
        }
        AnimatedVisibility(explaining, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            state.explanation?.let { ExplainOverlay(it, Modifier.fillMaxWidth().padding(14.dp)) }
        }
    }
}

@Composable
private fun PositionPicker(state: HandViewState, onPick: (Position) -> Unit, onUndo: () -> Unit, modifier: Modifier) {
    CakePanel(modifier) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("POSICIÓN DEL HÉROE", color = CakeColors.Cyan, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(state.heroCards.joinToString(" "), color = CakeColors.TextPrimary, fontFamily = Mono, fontWeight = FontWeight.Bold)
                CakeButton("↩ Undo", CakeColors.Purple, Modifier.padding(start = 8.dp).width(84.dp).fillMaxHeight(), enabled = state.canUndo,
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
                    !heroIn -> "MANO TERMINADA · HÉROE FOLDEA"
                    alive == 1 -> "MANO TERMINADA · EL HÉROE GANA ${bb(state.potBb)}bb"
                    else -> "SHOWDOWN · BOTE ${bb(state.potBb)}bb"
                },
                color = CakeColors.Gold, fontWeight = FontWeight.Black, fontSize = 18.sp,
            )
            Column(Modifier.fillMaxWidth().weight(1f)) {
                state.history.takeLast(8).forEach {
                    Text(it, color = CakeColors.TextSecondary, fontFamily = Mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CakeButton("↩ UNDO", CakeColors.Purple, Modifier.weight(1f).fillMaxHeight(), enabled = state.canUndo,
                    textColor = CakeColors.TextPrimary, onClick = onUndo)
                CakeButton("🔄 NUEVA MANO", CakeColors.Cyan, Modifier.weight(2f).fillMaxHeight(), fontSize = 18.sp, onClick = onNew)
            }
        }
    }
}
