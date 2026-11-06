package com.example.phonesteward.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * A15.6: the Read aloud action under a finished steward round. It turns into Stop while reading;
 * a failure shows one calm line and lets the owner try again.
 */
@Composable
fun ReadAloudButton(
    state: ReadAloudState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        when (state) {
            is ReadAloudState.Failed -> Text(
                state.message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("read-aloud-error"),
            )
            else -> Unit
        }
        val reading = state is ReadAloudState.Playing || state is ReadAloudState.Loading
        TextButton(
            onClick = onToggle,
            modifier = Modifier.testTag("read-aloud").semantics {
                contentDescription = if (reading) "Stop reading" else "Read aloud"
            },
        ) {
            Text(if (reading) "Stop" else "Read aloud", style = MaterialTheme.typography.labelMedium)
        }
    }
}
