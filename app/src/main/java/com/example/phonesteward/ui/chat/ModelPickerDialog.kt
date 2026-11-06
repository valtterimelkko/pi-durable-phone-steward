package com.example.phonesteward.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.phonesteward.logic.models.ModelPicker
import com.example.phonesteward.net.ModelCatalogueResponse
import com.example.phonesteward.net.ModelEntry
import com.example.phonesteward.ui.theme.Spacing

/**
 * The model picker (A10.4): a full-width sheet with a search box and every model of the Pi catalogue grouped by provider.
 * Unavailable models are greyed with the reason, pay-as-you-go ones are marked; tapping an available one chooses it (the
 * view model asks the one pay-as-you-go question when needed).
 */
@Composable
fun ModelPickerDialog(
    catalogue: ModelCatalogueResponse?,
    onChoose: (ModelEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 24.dp).testTag("model-picker"),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Choose a model", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("model-picker-close")) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("model-search"),
                    placeholder = { Text("Search models") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                )
                if (catalogue == null) {
                    Text(
                        "Loading the list…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp).testTag("model-loading"),
                    )
                } else {
                    val groups = remember(catalogue, query) { ModelPicker.groups(catalogue, query) }
                    if (groups.isEmpty()) {
                        Text(
                            "No model matches “${query.trim()}”.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp).testTag("model-none"),
                        )
                    }
                    LazyColumn(Modifier.fillMaxSize().testTag("model-list"), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
                        groups.forEach { group ->
                            item(key = "h-${group.title}") {
                                Text(
                                    group.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                                )
                            }
                            items(group.rows, key = { "${it.entry.provider}/${it.entry.id}" }) { row ->
                                ModelRowView(row) { onChoose(row.entry) }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRowView(row: ModelPicker.Row, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touch)
            .alpha(if (row.selectable) 1f else 0.5f)
            .then(if (row.selectable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag("model-row")
            .semantics {
                contentDescription = buildString {
                    append(ModelPicker.displayLabel(row))
                    if (row.isCurrent) append(", current")
                    row.badge?.let { append(", $it") }
                    row.note?.let { append(", $it") }
                    row.problem?.let { append(", $it") }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                // A15.10: an alias model is marked "(alias)" — its id is never a surprise.
                ModelPicker.displayLabel(row),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (row.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            )
            // A15.3: the catalogue's one-line reason this model currently fails, in plain words.
            row.problem?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("model-problem"),
                )
            }
            row.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        row.badge?.let { badge ->
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp).testTag("model-badge"),
                )
            }
        }
        if (row.isCurrent) {
            Icon(Icons.Filled.Check, contentDescription = "Current model", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
