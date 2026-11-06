package com.example.phonesteward.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.phonesteward.logic.ByteFormat
import com.example.phonesteward.logic.Onboarding
import com.example.phonesteward.logic.SpaceRow
import com.example.phonesteward.ui.theme.Spacing
import com.example.phonesteward.update.UpdateUiState

/**
 * Status: pairing, permissions with a confirmation for each, onboarding
 * steps, last sync, free space and the trend pointer.
 */
@Composable
fun StatusScreen(
    state: AppViewModel.AppState,
    space: SpaceRow,
    lastWorkerRunMs: Long,
    onboarding: Onboarding.Snapshot,
    onOpenBattery: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onOpenAllFiles: () -> Unit,
    onRequestMediaRead: () -> Unit,
    onOpenManageMedia: () -> Unit,
    onUnpair: () -> Unit,
    onRefresh: () -> Unit,
    update: UpdateUiState? = null,
    onUpdate: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    onEmptyTrash: () -> Unit = {},
    onDismissTrashResult: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    shizukuActions: ShizukuActions = ShizukuActions(),
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        // A7/F1: the one-tap self-update, first, because it is the only thing here to act on.
        if (update != null) UpdateSection(update, onUpdate, onCheckUpdate)

        SectionCard("Storage") {
            val used = (space.totalBytes - space.freeBytes).coerceAtLeast(0)
            if (space.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { used.toFloat() / space.totalBytes.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
            KeyValue("Free space", ByteFormat.format(space.freeBytes))
            KeyValue("Total", ByteFormat.format(space.totalBytes))
            Text(
                "The trend — what grew since last time — is built from the weekly checks on the server; ask in chat for the full breakdown.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Steward trash") {
            Text(
                com.example.phonesteward.logic.files.TrashText.describe(state.stewardTrash, java.time.ZoneId.systemDefault()),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("trash-text"),
            )
            state.trashResult?.let { ResultCard(it, onDismissTrashResult) }
            FilledTonalButton(
                onClick = onOpenTrash,
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touch).testTag("open-trash-button"),
            ) { Text("Open Steward trash") }
            // Owner rule 2026-10-08: no greyed, dead button. The button exists only when there is something to empty.
            if (state.stewardTrash.bytes > 0) {
                FilledTonalButton(
                    onClick = onEmptyTrash,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touch).testTag("empty-trash-button"),
                ) { Text("Empty Steward trash") }
            }
        }

        SectionCard("Sync") {
            KeyValue(
                "Last worker run",
                if (lastWorkerRunMs > 0) formatStamp(lastWorkerRunMs) else "not yet (every ~4 h while online)",
            )
            Text(
                "The app also long-polls while it is open, so proposals arrive immediately then.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val states = Onboarding.rowStates(onboarding)
        val allowed = states.count { it.second != Onboarding.RowState.ACTION_NEEDED }
        SectionCard("Permissions") {
            Text(
                "$allowed of ${states.size} in place",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("permissions-summary"),
            )
            states.forEachIndexed { index, (step, rowState) ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PermissionRow(
                    title = step.title,
                    description = step.how,
                    state = rowState,
                    buttonText = when (step) {
                        Onboarding.Step.BATTERY -> "Open battery settings"
                        Onboarding.Step.USAGE_ACCESS -> "Open usage access"
                        Onboarding.Step.ALL_FILES -> "Open all-files access"
                        Onboarding.Step.MEDIA_READ -> "Allow photos and videos"
                        Onboarding.Step.MANAGE_MEDIA -> "Allow managing media"
                    },
                    onButtonClick = when (step) {
                        Onboarding.Step.BATTERY -> onOpenBattery
                        Onboarding.Step.USAGE_ACCESS -> onOpenUsageAccess
                        Onboarding.Step.ALL_FILES -> onOpenAllFiles
                        Onboarding.Step.MEDIA_READ -> onRequestMediaRead
                        Onboarding.Step.MANAGE_MEDIA -> onOpenManageMedia
                    },
                )
            }
        }

        ShizukuCard(state.shizuku, shizukuActions)

        // (A10.3): operate the screen in an owner-started session.
        com.example.phonesteward.screen.PhoneUseSection()

        SectionCard("Pairing") {
            KeyValue("Server", state.baseUrl)
            Text(
                "Forget this phone's pairing. You will need a new pairing code to connect again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xs))
            FilledTonalButton(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touch),
            ) { Text("Refresh now") }
            TextButton(
                onClick = onUnpair,
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touch),
            ) { Text("Forget this pairing on the phone", color = MaterialTheme.colorScheme.error) }
        }

        state.notice?.let { NoticeCard(it, isError = true) }
        Text(
            "Phone Steward ${com.example.phonesteward.BuildConfig.VERSION_NAME} — metadata only: your photos never leave this phone.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xs),
        )
    }
}
