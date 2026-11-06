package com.example.phonesteward.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.phonesteward.logic.chat.ChatVerbosity
import com.example.phonesteward.ui.theme.StewardTheme

/**
 * F2: the Low / Medium / High control in the Chat top bar: a compact segmented pill.
 * Each segment keeps a 48 dp-high touch area inside a slimmer visible pill.
 */
@Composable
fun VerbosityControl(current: ChatVerbosity, onChange: (ChatVerbosity) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .padding(end = 12.dp)
            .testTag("verbosity-control")
            .semantics { contentDescription = "Chat detail: ${current.label}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(shape)
                .border(1.dp, StewardTheme.semantic.cardBorder, shape),
        ) {
            ChatVerbosity.entries.forEach { level ->
                val selected = level == current
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .widthIn(min = 52.dp)
                        .clickable { onChange(level) }
                        .semantics {
                            role = Role.RadioButton
                            this.selected = selected
                        }
                        .testTag("verbosity-${level.name}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .padding(horizontal = 2.dp)
                            .heightIn(min = 30.dp)
                            .clip(shape)
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            level.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
