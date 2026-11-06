package com.example.phonesteward.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.phonesteward.logic.ByteFormat
import com.example.phonesteward.ui.theme.Spacing
import com.example.phonesteward.ui.theme.StewardTheme
import com.example.phonesteward.update.UpdateUiState

/**
 * The Status screen's update card (A7, F1): the installed version with "Up to date", or
 * "Update available" with one Update button (one tap, then Android's own confirmation),
 * its progress, or the reason a download was refused.
 */
@Composable
fun UpdateSection(
    state: UpdateUiState,
    onUpdate: () -> Unit,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard("App update", modifier.testTag("update-card")) {
        Text(
            "App version ${state.installed}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.testTag("update-version"),
        )
        when (state) {
            is UpdateUiState.Unknown, is UpdateUiState.Checking ->
                Muted("Checking for updates…", "update-status")

            is UpdateUiState.UpToDate -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = StewardTheme.semantic.success, modifier = Modifier.size(20.dp))
                Text(
                    "Up to date",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StewardTheme.semantic.success,
                    modifier = Modifier.testTag("update-status"),
                )
            }

            is UpdateUiState.CheckFailed -> {
                Muted("Could not check for updates right now.", "update-status")
                TextButton(onClick = onCheck, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touch).testTag("update-check-button")) {
                    Text("Check again")
                }
            }

            is UpdateUiState.Available -> {
                Text(
                    "Update available: ${state.latest.versionName}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag("update-status"),
                )
                Muted(
                    "About ${ByteFormat.format(state.latest.sizeBytes)}. It downloads, is checked, and installs over this app: your pairing and data stay.",
                    "update-help",
                )
                UpdateButton("Update", onUpdate)
            }

            is UpdateUiState.NeedsPermission -> {
                Text(
                    "One switch first",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag("update-status"),
                )
                Muted(
                    "Android asks once: on the screen that opens, allow installs from Phone Steward, then come back. The update carries on by itself.",
                    "update-help",
                )
                UpdateButton("Open the switch again", onUpdate)
            }

            is UpdateUiState.Downloading -> {
                Text(
                    "Downloading ${state.latest.versionName}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag("update-status"),
                )
                val total = state.total.coerceAtLeast(1L)
                LinearProgressIndicator(
                    progress = { (state.bytes.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp).testTag("update-progress"),
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Muted("${ByteFormat.format(state.bytes)} of ${ByteFormat.format(state.total)}", "update-help")
            }

            is UpdateUiState.Verifying -> {
                Text("Checking the download…", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("update-status"))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp).testTag("update-progress"), strokeCap = StrokeCap.Round)
            }

            is UpdateUiState.Installing -> {
                Text("Waiting for Android's confirmation", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("update-status"))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp).testTag("update-progress"), strokeCap = StrokeCap.Round)
                Muted("Confirm on Android's dialog. The app restarts by itself, still paired.", "update-help")
            }

            is UpdateUiState.Refused -> {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(
                            "Update ${state.latest.versionName} refused",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("update-status"),
                        )
                        Muted("${state.reason.userText} Nothing was installed.", "update-help")
                    }
                }
                UpdateButton("Try again", onUpdate)
            }

            is UpdateUiState.Failed -> {
                Text("Update not installed", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("update-status"))
                Muted(state.message, "update-help")
                UpdateButton("Try again", onUpdate)
            }
        }
    }
}

@Composable
private fun Muted(text: String, tag: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun UpdateButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touch).testTag("update-button"),
    ) { Text(label) }
}
