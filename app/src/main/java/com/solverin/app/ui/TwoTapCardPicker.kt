package com.solverin.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solverin.app.presentation.HandViewState
import com.solverin.app.presentation.StreetFlowPhase
import com.solverin.engine.core.Card

/** Two-tap card input: rank (13 buttons) then suit (4 large four-colour buttons). Used cards are disabled. */
@Composable
fun TwoTapCardPicker(
    state: HandViewState,
    onRank: (Int) -> Unit,
    onSuit: (Int) -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CakePanel(modifier) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val title = when (state.phase) {
                StreetFlowPhase.PICK_HERO_CARDS -> "HOLE CARD ${state.heroCardIds.size + 1}/2"
                else -> when (state.boardIds.size) {
                    0, 1, 2 -> "FLOP CARD ${state.boardIds.size + 1}/3"
                    3 -> "TURN CARD"
                    else -> "RIVER CARD"
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = CakeColors.Cyan, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(state.pickerRank?.let { "Rank ${Card.RANK_CHARS[it]} → suit" } ?: "Tap rank", color = CakeColors.TextSecondary, fontSize = 12.sp)
                CakeButton("↶ Undo", CakeColors.Purple, Modifier.padding(start = 8.dp).width(84.dp).height(32.dp),
                    enabled = state.canUndo, textColor = CakeColors.TextPrimary, fontSize = 12.sp, onClick = onUndo)
            }
            val ranks = (12 downTo 0).toList()
            for (row in listOf(ranks.take(7), ranks.drop(7))) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { r ->
                        val free = (0..3).any { Card.id(r, it) !in state.usedCards }
                        val sel = state.pickerRank == r
                        CakeButton(Card.RANK_CHARS[r].toString(), if (sel) CakeColors.Cyan else CakeColors.BackgroundAlt,
                            Modifier.weight(1f).fillMaxHeight(), enabled = free,
                            textColor = if (sel) CakeColors.Ink else CakeColors.TextPrimary, fontSize = 20.sp) { onRank(r) }
                    }
                    if (row.size < 7) androidx.compose.foundation.layout.Spacer(Modifier.weight((7 - row.size).toFloat()))
                }
            }
            Row(Modifier.fillMaxWidth().weight(1.6f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (suit in 3 downTo 0) {
                    val r = state.pickerRank
                    val ok = r != null && Card.id(r, suit) !in state.usedCards
                    CakeButton(CakeColors.suitSymbols[suit], CakeColors.suitColors[suit], Modifier.weight(1f).fillMaxHeight(),
                        enabled = ok, fontSize = 34.sp) { onSuit(suit) }
                }
            }
        }
    }
}
