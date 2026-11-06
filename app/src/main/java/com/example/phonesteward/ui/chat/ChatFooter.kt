package com.example.phonesteward.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The line under the message box (A10.4, A10.9): the steward's current model (tap it to choose another) and how full its
 * working context is. Both come from the server; without them the footer says nothing rather than guess.
 */
@Composable
fun ChatFooter(
    modelLabel: String?,
    contextText: String?,
    onModelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (modelLabel == null && contextText == null) return
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("chat-footer"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (modelLabel != null) {
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onModelClick)
                    .semantics { role = Role.Button; contentDescription = "Model: $modelLabel. Tap to choose another." }
                    .testTag("chat-model"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modelLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
        if (contextText != null) {
            Text(
                contextText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp).testTag("chat-context"),
            )
        }
    }
}
