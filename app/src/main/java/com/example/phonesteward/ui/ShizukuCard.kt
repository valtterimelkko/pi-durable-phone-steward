package com.example.phonesteward.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.example.phonesteward.logic.shizuku.ShizukuState
import com.example.phonesteward.logic.shizuku.ShizukuText
import com.example.phonesteward.ui.theme.Spacing

/** What the Shizuku card's buttons do; the activity supplies the Android launches. */
class ShizukuActions(
    val onGet: () -> Unit = {},
    val onOpen: () -> Unit = {},
    val onAllow: () -> Unit = {},
    val onCheckAgain: () -> Unit = {},
)

/** Status: the optional Shizuku set-up, walked through in plain words (A10.2). */
@Composable
fun ShizukuCard(state: ShizukuState, actions: ShizukuActions, modifier: Modifier = Modifier) {
    val card = ShizukuText.card(state)
    SectionCard("Shizuku (optional)", modifier.testTag("shizuku-card")) {
        Text(card.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("shizuku-summary"))
        if (card.steps.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                card.steps.forEachIndexed { i, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        Text("${i + 1}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(step, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        card.primary?.let { action ->
            FilledTonalButton(
                onClick = {
                    when (action) {
                        ShizukuText.Action.GET_SHIZUKU -> actions.onGet()
                        ShizukuText.Action.OPEN_SHIZUKU -> actions.onOpen()
                        ShizukuText.Action.ALLOW_SHIZUKU -> actions.onAllow()
                        ShizukuText.Action.CHECK_AGAIN -> actions.onCheckAgain()
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touch).testTag("shizuku-primary"),
            ) { Text(ShizukuText.label(action), maxLines = 1) }
        }
        card.secondary?.let { action ->
            TextButton(
                onClick = actions.onCheckAgain,
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touch).testTag("shizuku-secondary"),
            ) { Text(ShizukuText.label(action)) }
        }
    }
}
