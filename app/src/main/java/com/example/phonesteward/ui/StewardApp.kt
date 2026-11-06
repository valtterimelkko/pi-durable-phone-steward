package com.example.phonesteward.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.example.phonesteward.logic.Onboarding
import com.example.phonesteward.logic.chat.ChatVerbosity
import com.example.phonesteward.ui.chat.ChatMenu
import com.example.phonesteward.ui.chat.VerbosityControl
import com.example.phonesteward.logic.SpaceRow
import com.example.phonesteward.net.PhotoWindow
import com.example.phonesteward.net.Proposal
import com.example.phonesteward.ui.theme.StewardIcons
import com.example.phonesteward.update.UpdateUiState

/**
 * The four tabs. [label] is the bottom-bar text (tests click it); [title] is
 * the top-bar text, deliberately different so each is found exactly once.
 */
enum class StewardTab(val label: String, val title: String, val icon: ImageVector) {
    Chat("Chat", "Steward", StewardIcons.Chat),
    Proposals("Proposals", "Open proposals", StewardIcons.Checklist),
    Rules("Rules", "Rules", Icons.Filled.Settings),
    Status("Status", "This phone", Icons.Filled.Info),
}

/** What the Status tab's buttons do; the activity supplies the Android launches. */
class StatusActions(
    val onOpenBattery: () -> Unit = {},
    val onOpenUsageAccess: () -> Unit = {},
    val onOpenAllFiles: () -> Unit = {},
    val onRequestMediaRead: () -> Unit = {},
    val onOpenManageMedia: () -> Unit = {},
    val onUnpair: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    /** F1: the Update button, and a manual "Check again". */
    val onUpdate: () -> Unit = {},
    val onCheckUpdate: () -> Unit = {},
    /** A10.1: Empty Steward trash (the app asks once before it deletes), and dismissing its result line. */
    val onEmptyTrash: () -> Unit = {},
    val onDismissTrashResult: () -> Unit = {},
    /** A11.3: the Steward trash restore screen (Status → Open Steward trash and back). */
    val onOpenTrash: () -> Unit = {},
    val onCloseTrash: () -> Unit = {},
    val onRestoreItem: (com.example.phonesteward.logic.files.TrashBatch, com.example.phonesteward.logic.files.TrashItem) -> Unit = { _, _ -> },
    val onRestoreBatch: (com.example.phonesteward.logic.files.TrashBatch) -> Unit = {},
    val onDismissTrashScreenResult: () -> Unit = {},
    /** A10.2: the Shizuku card's buttons. */
    val shizuku: ShizukuActions = ShizukuActions(),
)

/**
 * The whole app UI as a pure function of its state: pairing screen, or top
 * bar, the current tab and the bottom navigation. Every screen gets the
 * Scaffold's inner padding (N12), so nothing sits under a bar, plus the
 * keyboard inset. Tests render it from fixture [AppViewModel.AppState]s.
 */
@Composable
fun StewardApp(
    state: AppViewModel.AppState,
    tab: StewardTab,
    onTab: (StewardTab) -> Unit,
    space: SpaceRow,
    lastWorkerRunMs: Long,
    onboarding: Onboarding.Snapshot,
    statusActions: StatusActions,
    onPair: (code: String, deviceName: String, baseUrl: String) -> Unit,
    onSend: (String) -> Unit,
    onChatVerbosity: (ChatVerbosity) -> Unit = {},
    onDismissChatNotice: () -> Unit = {},
    onDecide: (proposal: Proposal, approve: Boolean, itemIds: List<String>) -> Unit,
    onDismissResult: () -> Unit,
    onSaveRules: (PhotoWindow) -> Unit,
    /** F1: the self-update row on Status; null hides it. */
    update: UpdateUiState? = null,
    /** The answer to the in-app confirmation in `state.confirm`. */
    onConfirm: (Boolean) -> Unit = {},
    /** A10.4 / A10.9: the Chat footer, model picker and menu. */
    agentActions: com.example.phonesteward.ui.ChatAgentActions = com.example.phonesteward.ui.ChatAgentActions(),
    onCompactNow: () -> Unit = {},
    onStartFresh: () -> Unit = {},
    /** A13.6: the phone-use pieces of Chat (request card, banner). */
    phoneUse: ChatPhoneUseActions = ChatPhoneUseActions(),
) {
    // The in-app confirmation draws over everything, in this window (the flake fix: never a second window).
    // While it shows, the screen behind it is cleared from the semantics tree, so assistive input cannot
    // reach or activate anything behind the scrim (correction 1, finding 5); touch is blocked by the scrim.
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        modifier = if (state.confirm != null) {
            Modifier.clearAndSetSemantics {}
        } else {
            Modifier
        },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (state.paired) {
                StewardTopBar(tab, state.trashOpen, statusActions.onCloseTrash) {
                    // F2: the Chat tab's Low / Medium / High control.
                    if (tab == StewardTab.Chat) {
                        VerbosityControl(state.chatVerbosity, onChatVerbosity)
                        ChatMenu(onCompactNow, onStartFresh)
                    }
                }
            }
        },
        bottomBar = { if (state.paired) StewardBottomBar(tab, onTab, state.proposals.size) },
    ) { padding ->
        // The inner padding already covers the bars and the system insets; mark
        // it consumed so the keyboard inset adds only what is still missing.
        val content = Modifier.padding(padding).consumeWindowInsets(padding).imePadding()
        if (!state.paired) {
            PairScreen(state, content, onPair)
        } else {
            when (tab) {
                StewardTab.Chat -> ChatScreen(
                    state, content, onSend,
                    onOpenProposals = { onTab(StewardTab.Proposals) },
                    onDismissNotice = onDismissChatNotice,
                    agentActions = agentActions,
                    phoneUse = phoneUse,
                )
                StewardTab.Proposals -> ProposalsScreen(state, content, onDismissResult, onDecide)
                StewardTab.Rules -> RulesScreen(state, content, onSaveRules)
                StewardTab.Status -> if (state.trashOpen) {
                    // A11.3: the trash screen; the top bar's back arrow returns to Status.
                    StewardTrashScreen(
                        state = state,
                        onRestoreItem = statusActions.onRestoreItem,
                        onRestoreBatch = statusActions.onRestoreBatch,
                        onDismissResult = statusActions.onDismissTrashScreenResult,
                        modifier = content,
                    )
                } else {
                    StatusScreen(
                        state = state,
                        space = space,
                        lastWorkerRunMs = lastWorkerRunMs,
                        onboarding = onboarding,
                        onOpenBattery = statusActions.onOpenBattery,
                        onOpenUsageAccess = statusActions.onOpenUsageAccess,
                        onOpenAllFiles = statusActions.onOpenAllFiles,
                        onRequestMediaRead = statusActions.onRequestMediaRead,
                        onOpenManageMedia = statusActions.onOpenManageMedia,
                        onUnpair = statusActions.onUnpair,
                        onRefresh = statusActions.onRefresh,
                        update = update,
                        onUpdate = statusActions.onUpdate,
                        onCheckUpdate = statusActions.onCheckUpdate,
                        onEmptyTrash = statusActions.onEmptyTrash,
                        onDismissTrashResult = statusActions.onDismissTrashResult,
                        onOpenTrash = statusActions.onOpenTrash,
                        shizukuActions = statusActions.shizuku,
                        modifier = content,
                    )
                }
            }
        }
    }
    state.confirm?.let { ConfirmDialog(it, onConfirm) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StewardTopBar(
    tab: StewardTab,
    trashOpen: Boolean,
    onBackFromTrash: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val showingTrash = trashOpen && tab == StewardTab.Status
    Column(Modifier.testTag("top-bar")) {
        TopAppBar(
            title = { Text(if (showingTrash) "Steward trash" else tab.title, style = MaterialTheme.typography.titleLarge) },
            navigationIcon = {
                if (showingTrash) {
                    IconButton(onClick = onBackFromTrash, modifier = Modifier.testTag("trash-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                scrolledContainerColor = MaterialTheme.colorScheme.background,
            ),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun StewardBottomBar(tab: StewardTab, onTab: (StewardTab) -> Unit, proposalCount: Int) {
    Column(Modifier.testTag("bottom-bar")) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = androidx.compose.ui.unit.Dp.Hairline,
        ) {
            StewardTab.entries.forEach { entry ->
                NavigationBarItem(
                    selected = tab == entry,
                    onClick = { onTab(entry) },
                    icon = {
                        if (entry == StewardTab.Proposals && proposalCount > 0) {
                            BadgedBox(badge = { Badge(Modifier.testTag("proposals-badge")) { Text(proposalCount.toString()) } }) {
                                Icon(entry.icon, contentDescription = null)
                            }
                        } else {
                            Icon(entry.icon, contentDescription = null)
                        }
                    },
                    label = { Text(entry.label) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}
