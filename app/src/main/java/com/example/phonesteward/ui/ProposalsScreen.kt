package com.example.phonesteward.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.phonesteward.logic.ByteFormat
import com.example.phonesteward.logic.ReviewStamp
import com.example.phonesteward.net.Proposal
import com.example.phonesteward.net.ProposalItem
import com.example.phonesteward.ui.theme.Spacing
import com.example.phonesteward.ui.theme.StewardIcons

/**
 * Proposals: list, item detail with sizes and reasons, tick and untick,
 * then Approve (which executes the approved items) or Reject. The outcome of
 * the last action shows as a result card at the top (N16).
 */
@Composable
fun ProposalsScreen(
    state: AppViewModel.AppState,
    modifier: Modifier = Modifier,
    onDismissResult: () -> Unit = {},
    onDecide: (proposal: Proposal, approve: Boolean, itemIds: List<String>) -> Unit,
) {
    val open = state.proposals
    Column(modifier.fillMaxSize()) {
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("busy-indicator"))
        }
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        // N16: the result card is the first item; bring it into view when a result arrives, whatever the scroll position.
        androidx.compose.runtime.LaunchedEffect(state.result) {
            if (state.result != null) listState.animateScrollToItem(0)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("proposals-list"),
            contentPadding = PaddingValues(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            state.result?.let { result ->
                item(key = "result") { ResultCard(result, onDismissResult) }
            }
            if (open.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = StewardIcons.Checklist,
                        title = "No open proposals",
                        body = "When the steward finds something worth doing — clearing a cache, uninstalling an unused app, " +
                            "trimming old media — it appears here for your approval.",
                    )
                }
            }
            items(open, key = { it.proposalId }) { proposal ->
                ProposalCard(proposal, state.busy, state.removedItemIds, onDecide)
            }
        }
    }
}

@Composable
private fun ProposalCard(
    proposal: Proposal,
    busy: Boolean,
    removed: Set<String>,
    onDecide: (Proposal, Boolean, List<String>) -> Unit,
) {
    val ticked = remember(proposal.proposalId) {
        mutableStateOf(proposal.items.filter { it.kind != "advice" }.map { it.itemId }.toSet())
    }
    StewardCard(Modifier.testTag("proposal-card")) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(proposal.title, style = MaterialTheme.typography.titleMedium)
                    ReviewStamp.text(proposal.createdAtMs)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SizePill(ByteFormat.format(proposal.bytesEstimate))
            }
            proposal.reason?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (proposal.items.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                proposal.items.forEachIndexed { index, item ->
                    ItemRow(item, ticked, removed = item.itemId in removed)
                    if (index < proposal.items.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                TextButton(
                    onClick = { onDecide(proposal, false, (ticked.value - removed).toList()) },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = Spacing.touch).testTag("reject-button"),
                ) {
                    Text("Reject")
                }
                Button(
                    onClick = { onDecide(proposal, true, (ticked.value - removed).toList()) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("approve-button"),
                ) {
                    Text("Approve", style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(
                approvalNote(proposal.items),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The note under the buttons: what approving really does for the kinds of item on THIS card
 * (L4: an uninstall card used to describe the cache screens).
 */
internal fun approvalNote(items: List<ProposalItem>): String {
    val kinds = items.map { it.kind }.toSet()
    if ("clear_all_caches" in kinds) {
        return "Clearing temporary files asks Android ONCE and clears EVERY app's cache — the biggest are listed above. " +
            "One confirmation, then done. Other actions keep their usual dialogs. Unticked items are kept."
    }
    val fileNote = "Approving files asks you once, in the app; they then move to the Steward trash, where they wait 30 days, " +
        "and nothing is gone for good until you empty it on Status."
    if (kinds.all { it == "delete_files" || it == "advice" }) return "$fileNote Unticked items are kept."
    val dialogs = buildList {
        if ("trash_media" in kinds) add("one for the whole media batch")
        if ("uninstall_app" in kinds) add("one for each uninstall")
        if ("clear_app_cache" in kinds) add("App info for a cache clear (tap Storage & cache, then Clear cache)")
    }
    if (dialogs.isEmpty()) return "This is advice only; approving changes nothing on the phone."
    val androidNote = "Approving opens Android's own confirmation: ${dialogs.joinToString(", ")}."
    return if ("delete_files" in kinds) "$androidNote $fileNote Unticked items are kept." else "$androidNote Unticked items are kept."
}

@Composable
private fun SizePill(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = Spacing.m, vertical = 6.dp),
        )
    }
}

@Composable
private fun ItemRow(item: ProposalItem, ticked: MutableState<Set<String>>, removed: Boolean = false) {
    val target = item.target
    val name = when (item.kind) {
        "clear_all_caches" -> clearAllCachesSummary(target)
        else -> (target["displayName"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: (target["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: (target["label"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: (target["packageName"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: (target["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: item.kind
    }
    val label = when (item.kind) {
        "trash_media" -> "Trash"
        "uninstall_app" -> "Uninstall"
        "clear_app_cache" -> "Clear cache of"
        "clear_all_caches" -> "Clear every app's cache"
        "delete_files" -> "Move to the Steward trash"
        "advice" -> "Note"
        else -> item.kind
    }
    val tickable = item.kind != "advice"
    // N31: an app that is already gone cannot be ticked; it is shown as "Already removed".
    val isTicked = item.itemId in ticked.value && !removed
    val toggle = {
        ticked.value = if (isTicked) ticked.value - item.itemId else ticked.value + item.itemId
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touch)
            .then(if (tickable && !removed) Modifier.clickable(onClick = toggle) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (tickable) {
            Checkbox(checked = isTicked, onCheckedChange = { toggle() }, enabled = !removed)
        } else {
            androidx.compose.foundation.layout.Spacer(Modifier.width(Spacing.m))
        }
        Column(Modifier.weight(1f).padding(vertical = Spacing.s), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(label) }
                    append(" · $name")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (tickable && !isTicked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (removed) {
                Text(
                    "Already removed — nothing left to do here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item.reason?.takeIf { !removed }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (tickable) {
            Text(
                ByteFormat.format(item.bytesEstimate),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xs, end = Spacing.xs),
            )
        }
    }
}

/** The biggest caches from a clear_all_caches target, for the approval screen (A6). */
private fun clearAllCachesSummary(target: kotlinx.serialization.json.JsonObject): String {
    val apps = (target["apps"] as? kotlinx.serialization.json.JsonArray)
        .orEmpty()
        .mapNotNull { it as? kotlinx.serialization.json.JsonObject }
        .mapNotNull { app ->
            val label = (app["label"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: (app["packageName"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val bytes = (app["cacheBytes"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            label?.let { it to bytes }
        }
    if (apps.isEmpty()) return "every app's cache"
    return apps.joinToString(", ") { (label, bytes) -> "$label ${ByteFormat.format(bytes)}" }
}
