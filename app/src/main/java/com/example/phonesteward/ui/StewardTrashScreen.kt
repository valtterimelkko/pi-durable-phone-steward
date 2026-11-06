package com.example.phonesteward.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import com.example.phonesteward.logic.ByteFormat
import com.example.phonesteward.logic.files.TrashBatch
import com.example.phonesteward.logic.files.TrashItem
import com.example.phonesteward.ui.theme.Spacing

/**
 * A11.3: the Steward trash, by batch, newest first. Each batch shows its date, the proposal's title (or its id), its
 * size and its items (original path and size) with Restore per item and Restore all per batch. A restore asks nothing:
 * it puts things back, so nothing is lost. An item something now stands at is shown with its plain reason and no
 * button (the owner rule: no greyed, dead button). While a restore runs both buttons are held, so a double tap cannot
 * race it (Correction 1) — the wait lasts only that restore. Stateless, so tests and previews render it from a fixture
 * state.
 */
@Composable
fun StewardTrashScreen(
    state: AppViewModel.AppState,
    onRestoreItem: (TrashBatch, TrashItem) -> Unit,
    onRestoreBatch: (TrashBatch) -> Unit,
    onDismissResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val titles = remember(state.proposals, state.chat) { proposalTitles(state) }
    val batches = state.trashBatches
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("trash-list"),
        contentPadding = PaddingValues(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        state.trashScreenResult?.let { result ->
            item(key = "result") { ResultCard(result, onDismissResult) }
        }
        if (batches != null && batches.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Filled.Delete,
                    title = TrashScreenText.EMPTY_TITLE,
                    body = TrashScreenText.EMPTY_BODY,
                )
            }
        }
        items(batches.orEmpty(), key = { "${it.date}/${it.proposalId}" }) { batch ->
            TrashBatchCard(
                batch = batch,
                title = titles[batch.proposalId],
                restoring = state.trashRestoring,
                onRestoreItem = { onRestoreItem(batch, it) },
                onRestoreBatch = { onRestoreBatch(batch) },
            )
        }
    }
}

@Composable
private fun TrashBatchCard(
    batch: TrashBatch,
    title: String?,
    restoring: Boolean,
    onRestoreItem: (TrashItem) -> Unit,
    onRestoreBatch: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    StewardCard(Modifier.testTag("trash-batch")) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(TrashScreenText.date(batch.dateMs, zone), style = MaterialTheme.typography.titleMedium)
                    Text(
                        TrashScreenText.batchTitle(batch.proposalId, title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${TrashScreenText.itemsLabel(batch.items.size)} · ${ByteFormat.format(batch.sizeBytes)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            for (item in batch.items) {
                TrashItemRow(item, restoring = restoring, onRestore = { onRestoreItem(item) })
            }
            FilledTonalButton(
                onClick = onRestoreBatch,
                enabled = !restoring,
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touch).testTag("restore-all"),
            ) { Text("Restore all") }
        }
    }
}

@Composable
private fun TrashItemRow(item: TrashItem, restoring: Boolean, onRestore: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Spacing.m), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.path, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            Text(
                if (item.blocked) item.reason.orEmpty() else ByteFormat.format(item.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (item.blocked) Modifier.testTag("trash-item-blocked") else Modifier,
            )
        }
        if (!item.blocked) {
            TextButton(
                onClick = onRestore,
                enabled = !restoring,
                modifier = Modifier.heightIn(min = Spacing.touch).testTag("restore-item"),
            ) { Text("Restore") }
        }
    }
}

/** First the open proposals' titles, then the titles the chat stream carried; the proposal id when neither has one. */
private fun proposalTitles(state: AppViewModel.AppState): Map<String, String> {
    val titles = LinkedHashMap<String, String>()
    for (entry in state.chat) {
        if (entry.kind == "proposal" && entry.proposalId != null && entry.text.isNotBlank()) {
            titles.putIfAbsent(entry.proposalId, entry.text)
        }
    }
    for (proposal in state.proposals) {
        titles[proposal.proposalId] = proposal.title
    }
    return titles
}
