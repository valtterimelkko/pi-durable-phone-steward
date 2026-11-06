package com.example.phonesteward.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.phonesteward.logic.RulesForm
import com.example.phonesteward.net.PhotoWindow
import com.example.phonesteward.ui.theme.Spacing

private const val FAVOURITES_NOTICE =
    "Some gallery apps' favourites are not visible to this app (a vendor quirk). Put a photo in a folder or album named Keep to protect it."
private const val AMAZON_NOTICE =
    "The agent cannot see your cloud photo backup — back up what you want to keep yourself; anything you leave is treated as not wanted after the cut-off."

/** Rules: the photo window  with optimistic save and conflict handling, and the full-screen phone-use app picker (A14). */
@Composable
fun RulesScreen(
    state: AppViewModel.AppState,
    modifier: Modifier = Modifier,
    onSave: (PhotoWindow) -> Unit,
) {
    // (finding 3): the picker opens as a full-size surface over the Rules content,
    // so the search field and the rows are on screen the moment Add an app is tapped.
    var pickerTarget by remember { mutableStateOf<com.example.phonesteward.screen.GuardPickerTarget?>(null) }
    var pickerRevision by remember { mutableIntStateOf(0) }
    Box(modifier.fillMaxSize()) {
        RulesContent(
            state = state,
            onSave = onSave,
            pickerRevision = pickerRevision,
            onOpenPicker = { target -> pickerTarget = target },
        )
        pickerTarget?.let { target ->
            com.example.phonesteward.screen.PhoneUsePickerOverlay(
                target = target,
                onDismiss = {
                    pickerTarget = null
                    pickerRevision++
                },
            )
        }
    }
}

@Composable
private fun RulesContent(
    state: AppViewModel.AppState,
    onSave: (PhotoWindow) -> Unit,
    pickerRevision: Int,
    onOpenPicker: (com.example.phonesteward.screen.GuardPickerTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rules = state.rules
    if (rules == null) {
        EmptyState(
            icon = Icons.Filled.Settings,
            title = "Rules are not loaded yet",
            body = "Pair and sync once; the rules come from the server.",
            modifier = modifier.fillMaxSize(),
        )
        return
    }
    val window = rules.photoWindow
    var enabled by remember(rules.ruleVersion) { mutableStateOf(window.enabled) }
    var reminderDays by remember(rules.ruleVersion) { mutableIntStateOf(window.reminderDays) }
    var cutoffDays by remember(rules.ruleVersion) { mutableIntStateOf(window.cutoffDays) }
    var autoMode by remember(rules.ruleVersion) { mutableStateOf(window.autoMode) }

    val autoAllowed = RulesForm.autoModeAllowed(rules.batchesRunOneTap)
    val problems = RulesForm.validate(
        PhotoWindow(enabled, reminderDays, cutoffDays, autoMode, window.folders, RulesForm.FIXED_KEEP_MARKER),
    )

    Column(modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            SectionCard("Photo window") {
                SwitchRow("Rule enabled", "Keep a rolling window; the steward reminds you first.", enabled) { enabled = it }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                StepperRow("Reminder age", reminderDays, 30, 180, 15) { reminderDays = it }
                StepperRow("Cut-off age", cutoffDays, 60, 365, 15) { cutoffDays = it }
                Text(
                    "At the reminder age items are listed for you; past the cut-off they go to the trash (recoverable for about 30 days).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchRow(
                    "Auto mode (no confirmation dialog)",
                    if (autoAllowed) "Media is trashed silently once this rule runs."
                    else "Unlocks after the first one-tap batch has run (run so far: ${rules.batchesRunOneTap}).",
                    autoMode,
                    enabled = autoAllowed,
                ) { autoMode = it }
            }

            SectionCard("Protected items") {
                Text(FAVOURITES_NOTICE, style = MaterialTheme.typography.bodyMedium)
                Text(
                    AMAZON_NOTICE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Keep marker: Keep (fixed in this version — a folder or album named Keep protects a photo)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Covered folders:", style = MaterialTheme.typography.labelMedium)
                    window.folders.forEach { folder ->
                        Text(
                            "• $folder",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    }
                }
                Text(
                    "Folder organisation is out of scope for the MVP; the rule reads these folders where they are.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // (A10.3): the apps a phone-use session may and may not use.
            com.example.phonesteward.screen.PhoneUseRulesSection(
                revision = pickerRevision,
                onOpenPicker = onOpenPicker,
            )

            problems.messages().forEach { message -> NoticeCard(message, isError = true) }
            state.notice?.let { NoticeCard(it) }
            KeyValue("Rule version", rules.ruleVersion.toString())
        }

        // The save bar stays in reach above the bottom navigation.
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    TextButton(
                        onClick = {
                            enabled = window.enabled
                            reminderDays = window.reminderDays
                            cutoffDays = window.cutoffDays
                            autoMode = window.autoMode
                        },
                        modifier = Modifier.heightIn(min = Spacing.touch),
                    ) {
                        Text("Reset view")
                    }
                    Button(
                        onClick = {
                            onSave(PhotoWindow(enabled, reminderDays, cutoffDays, autoMode, window.folders, RulesForm.FIXED_KEEP_MARKER))
                        },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("save-rules"),
                    ) {
                        Text(if (state.busy) "Saving…" else "Save rules", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Spacing.touch),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun StepperRow(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Spacing.touch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        FilledTonalIconButton(
            onClick = { onChange((value - step).coerceAtLeast(min)) },
            enabled = value > min,
            modifier = Modifier.size(Spacing.touch),
        ) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(
            "$value days",
            Modifier.padding(horizontal = Spacing.m).widthIn(min = 72.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        FilledTonalIconButton(
            onClick = { onChange((value + step).coerceAtMost(max)) },
            enabled = value < max,
            modifier = Modifier.size(Spacing.touch),
        ) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}
