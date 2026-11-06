package com.example.phonesteward.voice

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A15.5: the microphone button in the Chat input. Tap to record, tap again to stop; while
 * recording it shows the elapsed time and a cancel. Errors are one line, in the row.
 */
@Composable
fun DictationButton(
    state: DictationState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        when (state) {
            is DictationState.Idle -> TextButton(
                onClick = onStart,
                modifier = Modifier.testTag("chat-dictate").semantics { contentDescription = "Dictate" },
            ) { Text("Dictate", style = MaterialTheme.typography.labelMedium) }

            is DictationState.Recording -> {
                Text(
                    elapsed(state.elapsedMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("chat-dictate-elapsed").padding(end = 2.dp),
                )
                TextButton(
                    onClick = onStop,
                    modifier = Modifier.testTag("chat-dictate").semantics { contentDescription = "Stop dictation" },
                ) { Text("Stop", style = MaterialTheme.typography.labelMedium) }
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("chat-dictate-cancel").semantics { contentDescription = "Cancel dictation" },
                ) { Icon(Icons.Filled.Close, contentDescription = null) }
            }

            is DictationState.Transcribing -> Text(
                "Transcribing…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("chat-dictate-busy").padding(horizontal = 8.dp),
            )

            is DictationState.Failed -> Text(
                state.message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("chat-dictate-error").padding(horizontal = 8.dp),
            )
        }
    }
}

private fun elapsed(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
