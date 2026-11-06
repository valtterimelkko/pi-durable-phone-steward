@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.phonesteward.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.example.phonesteward.android.AppGraph
import com.example.phonesteward.ui.SectionCard
import com.example.phonesteward.ui.theme.Spacing

/** Which list the picker adds to. */
enum class GuardPickerTarget { ALLOW, DENY }

/**
 * Rules: the apps a phone-use session may use (allow list) and may never use (deny list).
 * A14: entries show the app's name with its id under it (or the id and "not installed"), and apps are
 * added from a picker of the installed apps, never a free-text id. Saved on each change, in this app
 * only; the model cannot read or change them. Banking, wallet, authenticator and password-manager
 * names stay denied whatever is listed here.
 *
 * [revision] bumps when the picker overlay (rendered by the Rules screen, above this card) closes,
 * so rows re-read the store after a pick. [onOpenPicker] asks the screen to open it.
 */
@Composable
fun PhoneUseRulesSection(
    revision: Int,
    onOpenPicker: (GuardPickerTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context.applicationContext) }
    val installedApps = graph.installedApps
    val store = remember { PrefsGuardListStore(context) }
    var lists by remember { mutableStateOf(store.load()) }
    // A14.1: launcher labels, resolved off the main thread; null until the first resolution ran.
    var labels by remember { mutableStateOf<Map<String, String?>?>(null) }
    // Correction 1 (P14): what a reload just added — the confirmation names it and its row is
    // brought into view. Cleared after a moment and by any direct edit.
    var added by remember { mutableStateOf<Pair<String, GuardPickerTarget>?>(null) }

    LaunchedEffect(revision) {
        if (revision > 0) {
            val fresh = store.load() // a pick from the overlay changed the store
            added = GuardListUi.addedEntry(lists, fresh)
            lists = fresh
        }
    }
    LaunchedEffect(added) {
        if (added != null) {
            delay(6_000) // the notice is a moment's confirmation, not a state
            added = null
        }
    }
    LaunchedEffect(lists, installedApps) {
        val resolved = withContext(Dispatchers.IO) {
            (lists.allow + lists.deny).distinct().associateWith { installedApps.label(it) }
        }
        labels = resolved
    }

    SectionCard("Phone use: apps", modifier) {
        Text(
            "The steward may only use the apps on the allow list, and never those on the deny list. " +
                "Entering a denied app ends a session. Banking, wallet, authenticator and password apps are always denied.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        added?.let { (pkg, target) ->
            Text(
                // Correction 1 (P14): a pick says so, by the name the owner knows.
                "Added ${labels?.get(pkg) ?: pkg} to ${if (target == GuardPickerTarget.ALLOW) "Allowed" else "Never allowed"}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("phone-use-added-confirm"),
            )
        }
        GuardPackageList(
            title = "Allowed",
            tag = "phone-use-allow-list",
            entries = lists.allow,
            labels = labels,
            addTag = "phone-use-allow-add",
            addedPackage = added?.takeIf { it.second == GuardPickerTarget.ALLOW }?.first,
            onAdd = { onOpenPicker(GuardPickerTarget.ALLOW) },
            onRemove = { pkg -> added = null; lists = store.load().without(pkg).also { store.save(it) } },
        )
        GuardPackageList(
            title = "Never allowed",
            tag = "phone-use-deny-list",
            entries = lists.deny,
            labels = labels,
            addTag = "phone-use-deny-add",
            addedPackage = added?.takeIf { it.second == GuardPickerTarget.DENY }?.first,
            onAdd = { onOpenPicker(GuardPickerTarget.DENY) },
            onRemove = { pkg -> added = null; lists = store.load().without(pkg).also { store.save(it) } },
        )
        TextButton(
            onClick = { added = null; store.resetToDefaults(); lists = store.load() },
            modifier = Modifier.heightIn(min = Spacing.touch).testTag("phone-use-reset-lists"),
        ) { Text("Reset both lists") }
    }
}

/** One list (Allowed / Never allowed): its rows by name and the Add an app button (A14.1, A14.2). */
@Composable
private fun GuardPackageList(
    title: String,
    tag: String,
    entries: Set<String>,
    labels: Map<String, String?>?,
    addTag: String,
    addedPackage: String?,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        // Correction 1 (P14): the owner reads names, not package ids.
        GuardListUi.sortedEntries(entries, labels ?: emptyMap()).forEach { pkg ->
            val resolved = labels?.containsKey(pkg) == true
            val rowText = GuardRowText.of(pkg, labels?.get(pkg), resolved)
            val requester = remember(pkg) { BringIntoViewRequester() }
            LaunchedEffect(pkg == addedPackage) {
                if (pkg == addedPackage) requester.bringIntoView()
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(requester)
                    .testTag("${tag.removeSuffix("-list")}-row-$pkg"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        rowText.title,
                        style = MaterialTheme.typography.bodyMedium,
                        // A name reads in the text face; the package id (pending or not installed) in mono.
                        fontFamily = if (resolved && labels?.get(pkg) != null) FontFamily.Default else FontFamily.Monospace,
                    )
                    rowText.subtitle?.let { sub ->
                        if (sub == GuardRowText.NOT_INSTALLED) {
                            // A14.1: a lookup that answered null — the id and "not installed".
                            Text(
                                sub,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                sub,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                TextButton(
                    onClick = { onRemove(pkg) },
                    modifier = Modifier.heightIn(min = Spacing.touch),
                ) { Text("Remove") }
            }
        }
        FilledTonalButton(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touch).testTag(addTag),
        ) { Text("Add an app") }
    }
}

/**
 * A14.2/A14.3 as a full-screen surface over the Rules content (Correction 1, finding 3): the search
 * field and the app rows are on screen the moment the owner taps Add an app — no scrolling to find
 * the picker. It stays in this window (no dialog), so tests can drive it normally. One tap adds —
 * moving the app out of the other list — and closes (A14.2); in the allow picker an always-denied
 * app is marked "always denied" and its row does nothing (A14.3); an app on the other list is
 * marked "on …" and moves on tap.
 */
@Composable
fun PhoneUsePickerOverlay(target: GuardPickerTarget, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context.applicationContext) }
    val installedApps = graph.installedApps
    val store = remember { PrefsGuardListStore(context) }
    val forAllow = target == GuardPickerTarget.ALLOW
    var query by remember { mutableStateOf("") }
    // A15.11: the switch lists installed packages with no launcher entry too (Play services and
    // the like), so an app the model needs can be added permanently.
    var showSystem by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var lists by remember { mutableStateOf(store.load()) }
    LaunchedEffect(installedApps, showSystem) {
        apps = null
        apps = withContext(Dispatchers.IO) {
            if (showSystem) installedApps.installed() else installedApps.launchable()
        }
    }
    BackHandler(onBack = onDismiss)

    fun pick(pkg: String) {
        val saved = if (forAllow) store.load().addingToAllow(pkg) else store.load().addingToDeny(pkg)
        store.save(saved)
        lists = saved
        onDismiss()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (forAllow) "Add an app to allow" else "Add an app to deny",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = Spacing.touch),
                ) { Text("Close") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("app-picker-search"),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Show system apps",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.Switch(
                    checked = showSystem,
                    onCheckedChange = { showSystem = it },
                    modifier = Modifier.testTag("app-picker-system"),
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .testTag("app-picker"),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                val loaded = apps
                if (loaded == null) {
                    Text(
                        "Looking for apps…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val rows = AppPickerLogic.rows(
                        apps = loaded,
                        query = query,
                        exclude = if (forAllow) lists.allow else lists.deny,
                        otherList = if (forAllow) lists.deny else lists.allow,
                        forAllow = forAllow,
                    )
                    if (rows.isEmpty()) {
                        Text(
                            "No app matches.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    rows.forEach { row -> PickerRow(row, forAllow, installedApps, onAdd = { pick(row.app.packageName) }) }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(row: AppPickerLogic.Row, forAllow: Boolean, installedApps: InstalledApps, onAdd: () -> Unit) {
    var icon by remember(row.app.packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(row.app.packageName) {
        icon = withContext(Dispatchers.IO) {
            // Explicit size: some drawables (flat colours among them) have no intrinsic
            // dimensions, and toBitmap() at the intrinsic size would throw on them.
            installedApps.icon(row.app.packageName)?.toBitmap(96, 96)?.asImageBitmap()
        }
    }
    val base = Modifier
        .fillMaxWidth()
        .heightIn(min = Spacing.touch)
        .testTag("app-picker-item-${row.app.packageName}")
    Row(
        modifier = if (row.addable) base.clickable(onClick = onAdd) else base,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        AppIcon(icon, row.app.label)
        Column(Modifier.weight(1f)) {
            Text(row.app.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                row.app.packageName,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            row.alwaysDenied -> Text(
                "always denied",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            row.onOtherList -> Text(
                if (forAllow) "on Never allowed" else "on Allowed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The app's icon; a letter avatar while it loads or when there is none (the fakes have none). */
@Composable
private fun AppIcon(icon: ImageBitmap?, label: String) {
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(36.dp))
    } else {
        Box(
            Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(label.take(1).uppercase(), style = MaterialTheme.typography.labelMedium)
        }
    }
}
