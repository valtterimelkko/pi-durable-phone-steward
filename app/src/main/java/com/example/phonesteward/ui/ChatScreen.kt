package com.example.phonesteward.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.phonesteward.logic.chat.ChatRow
import com.example.phonesteward.logic.chat.ChatRows
import com.example.phonesteward.logic.chat.DividerRow
import com.example.phonesteward.logic.chat.MessageRow
import com.example.phonesteward.logic.chat.NoteRow
import com.example.phonesteward.logic.chat.PhoneResultRow
import com.example.phonesteward.logic.chat.PhoneUseAskRow
import com.example.phonesteward.logic.chat.PhoneUseCards
import com.example.phonesteward.logic.chat.PhoneUseEndedRow
import com.example.phonesteward.logic.chat.PhoneUseRequestRow
import com.example.phonesteward.logic.chat.PhoneUseStartedRow
import com.example.phonesteward.logic.chat.PhoneUseStepGroupRow
import com.example.phonesteward.logic.chat.PhoneUseStepRow
import com.example.phonesteward.logic.chat.ProposalRow
import com.example.phonesteward.logic.chat.RunRow
import com.example.phonesteward.logic.chat.ToolCardRow
import com.example.phonesteward.logic.chat.ToolGroupRow
import com.example.phonesteward.logic.chat.WorkingRow
import com.example.phonesteward.ui.chat.ChatComposer
import com.example.phonesteward.ui.chat.ChatFooter
import com.example.phonesteward.ui.chat.ModelPickerDialog
import com.example.phonesteward.ui.chat.DividerRowView
import com.example.phonesteward.ui.chat.MessageRowView
import com.example.phonesteward.ui.chat.NoteRowView
import com.example.phonesteward.ui.chat.PhoneResultRowView
import com.example.phonesteward.ui.chat.PhoneUseAskRowView
import com.example.phonesteward.ui.chat.PhoneUseEndedRowView
import com.example.phonesteward.ui.chat.PhoneUseRequestRowView
import com.example.phonesteward.ui.chat.PhoneUseStartedRowView
import com.example.phonesteward.ui.chat.PhoneUseStepGroupRowView
import com.example.phonesteward.ui.chat.PhoneUseStepRowView
import com.example.phonesteward.ui.chat.ProposalRowView
import com.example.phonesteward.ui.chat.RunRowView
import com.example.phonesteward.ui.chat.ToolCardRowView
import com.example.phonesteward.ui.chat.ToolGroupRowView
import com.example.phonesteward.ui.chat.WorkingRowView
import com.example.phonesteward.voice.DictationButton
import com.example.phonesteward.voice.ReadAloudButton
import com.example.phonesteward.voice.RoundText
import com.example.phonesteward.voice.rememberVoiceSession

/**
 * Chat (F2): the conversation as the Pi Web UI shows a session stream, at the verbosity in
 * `state.chatVerbosity` (Low, Medium, High). History and the live stream arrive as
 * `state.chat`; [ChatRows] maps them to rows, the views here draw them.
 */
@Composable
fun ChatScreen(
    state: AppViewModel.AppState,
    modifier: Modifier = Modifier,
    onSend: (String) -> Unit,
    onOpenProposals: () -> Unit = {},
    onDismissNotice: () -> Unit = {},
    agentActions: ChatAgentActions = ChatAgentActions(),
    phoneUse: ChatPhoneUseActions = ChatPhoneUseActions(),
) {
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // Wall-clock time is read when the data changes, so timestamps and the stale-run rule
    // are stable between recompositions.
    val nowMs = remember(state.chat) { System.currentTimeMillis() }
    // A13.6: the request cards need the service switch; re-read when the app comes back.
    val context = androidx.compose.ui.platform.LocalContext.current
    var serviceOn by remember { mutableStateOf(com.example.phonesteward.screen.StewardAccessibilityService.isEnabled(context)) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) serviceOn = com.example.phonesteward.screen.StewardAccessibilityService.isEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // A15.5/A15.6: dictation and read aloud, bound to the paired identity. The recorder and
    // player sit behind interfaces, so Test Lab (which has no microphone) can substitute fakes.
    val voice = rememberVoiceSession { text -> draft = insertAtCursor(draft, text) }
    val dictationState by voice.dictation.state.collectAsState()
    val readAloudState by voice.readAloud.state.collectAsState()
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.dictation.start()
    }
    fun dictate() {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            voice.dictation.start()
        } else {
            askMicrophone.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }
    // A15.10: the model chosen in Settings, so a round answered by another one says so plainly.
    val chosenModel = state.agent?.model?.let { current ->
        com.example.phonesteward.net.AnsweringModel(
            current.provider,
            current.id,
            com.example.phonesteward.logic.models.ModelPicker.footerLabel(current, state.models),
        )
    }
    val rows = remember(state.chat, state.chatVerbosity, nowMs, state.phoneUsePendingId, serviceOn, chosenModel) {
        ChatRows.build(
            state.chat, state.chatVerbosity, nowMs,
            PhoneUseCards(state.phoneUsePendingId, serviceOn),
            chosenModel = chosenModel,
        )
    }
    // A15.6: one Read aloud button per finished steward round, under its last message row.
    val speechByKey = remember(rows) {
        RoundText.rounds(rows).filter { it.finished }.associateBy { it.key }
    }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    // A13.6: the running session's banner and the confirm gate's question (process-wide objects).
    val phoneUi by com.example.phonesteward.screen.PhoneUseState.state.collectAsState()
    val confirmAsking by com.example.phonesteward.screen.ConfirmBroker.asking.collectAsState()
    val sessionActive = phoneUi is com.example.phonesteward.screen.PhoneUseUi.Starting ||
        phoneUi is com.example.phonesteward.screen.PhoneUseUi.Running
    // The one-time notification permission before an Allow starts the session (the gate's
    // questions ride the notification otherwise).
    var pendingAllow by remember { mutableStateOf<String?>(null) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingAllow?.let { phoneUse.onAllow(it) }
        pendingAllow = null
    }

    // The newest row is shown whenever one is added (the first time without animation).
    var firstScroll by remember { mutableStateOf(true) }
    LaunchedEffect(rows.size, rows.lastOrNull()?.key) {
        if (rows.isNotEmpty()) {
            val distance = kotlin.math.abs(rows.lastIndex - listState.firstVisibleItemIndex)
            if (firstScroll || distance > 10) {
                listState.scrollToItem(rows.lastIndex)
                firstScroll = false
            } else {
                listState.animateScrollToItem(rows.lastIndex)
            }
        }
    }

    // N12: `modifier` carries the Scaffold's inner padding (and the keyboard inset), so the
    // input row sits above the bottom bar.
    Column(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("chat-list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (rows.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Ask your steward anything — \"what is eating my storage?\" — or wait for its weekly check.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(rows, key = { it.key }) { row ->
                ChatRowView(
                    row = row,
                    nowMs = nowMs,
                    expanded = row.key in expanded,
                    onToggle = { expanded = if (row.key in expanded) expanded - row.key else expanded + row.key },
                    onOpenProposals = onOpenProposals,
                    serviceOn = serviceOn,
                    phoneUse = phoneUse,
                    readAloud = speechByKey[row.key]?.let { round ->
                        { ReadAloudButton(state = readAloudState, onToggle = { voice.readAloud.toggle(round.text) }) }
                    },
                    onAllowWithPermission = { requestId ->
                        if (android.Manifest.permission.POST_NOTIFICATIONS.let {
                                context.checkSelfPermission(it)
                            } == android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            phoneUse.onAllow(requestId)
                        } else {
                            pendingAllow = requestId
                            askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
            }
        }
        state.chatNotice?.let { ChatNotice(it, onDismissNotice) }
        state.chatInfo?.let { ChatNotice(it, agentActions.onDismissInfo, isError = false) }
        if (sessionActive) {
            PhoneUseBanner(
                task = (phoneUi as? com.example.phonesteward.screen.PhoneUseUi.Running)?.task
                    ?: (phoneUi as? com.example.phonesteward.screen.PhoneUseUi.Starting)?.task.orEmpty(),
                onStop = phoneUse.onStopSession,
            )
        }
        confirmAsking?.let { asking ->
            ConfirmGateCard(
                request = asking,
                // The card answers the question it was drawn for (H2): a tap a frame late, after
                // this question timed out and a newer one arrived, must answer nothing.
                onAnswer = { allowed -> com.example.phonesteward.screen.ConfirmBroker.answer(allowed, asking) },
            )
        }
        ChatComposer(
            draft = draft,
            onDraft = { draft = it },
            onSend = {
                val text = draft.text.trim()
                if (text.isNotEmpty()) {
                    onSend(text)
                    draft = TextFieldValue("")
                }
            },
            dictation = {
                DictationButton(
                    state = dictationState,
                    onStart = { dictate() },
                    onStop = { voice.dictation.stop() },
                    onCancel = { voice.dictation.cancel() },
                )
            },
        )
        // A10.4 / A10.9: the current model (tap to choose another) and "Context NN %".
        ChatFooter(
            modelLabel = state.agent?.let { com.example.phonesteward.logic.models.ModelPicker.footerLabel(it.model, state.models) },
            contextText = com.example.phonesteward.logic.context.ContextFooter.contextText(state.agent?.context),
            onModelClick = { picking = true; agentActions.onLoadModels() },
        )
    }
    if (picking) {
        ModelPickerDialog(
            catalogue = state.models,
            onChoose = { entry ->
                picking = false
                agentActions.onChooseModel(entry)
            },
            onDismiss = { picking = false },
        )
    }
}

/** What the Chat footer, picker and menu do; the view model supplies them. */
class ChatAgentActions(
    val onLoadModels: () -> Unit = {},
    val onChooseModel: (com.example.phonesteward.net.ModelEntry) -> Unit = {},
    val onDismissInfo: () -> Unit = {},
)

/** A13.6: what the phone-use pieces of Chat do; the view model supplies them. */
class ChatPhoneUseActions(
    /** Answers the request route; starts the session on an Allow. */
    val onAllow: (requestId: String) -> Unit = {},
    val onDecline: (requestId: String) -> Unit = {},
    /** The "Go to Status" link when the Accessibility service is off. */
    val onOpenStatus: () -> Unit = {},
    /** The banner's Stop — the same thing the notification's Stop does. */
    val onStopSession: () -> Unit = {},
)

@Composable
private fun ChatRowView(
    row: ChatRow,
    nowMs: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenProposals: () -> Unit,
    serviceOn: Boolean,
    phoneUse: ChatPhoneUseActions,
    onAllowWithPermission: (String) -> Unit,
    readAloud: (@Composable () -> Unit)? = null,
) {
    when (row) {
        is MessageRow -> MessageRowView(row, nowMs, readAloud)
        is NoteRow -> NoteRowView(row)
        is PhoneResultRow -> PhoneResultRowView(row)
        is ToolGroupRow -> ToolGroupRowView(row, expanded, onToggle)
        is ToolCardRow -> ToolCardRowView(row)
        is ProposalRow -> ProposalRowView(row, onOpenProposals)
        is RunRow -> RunRowView(row, nowMs)
        is WorkingRow -> WorkingRowView(row)
        is DividerRow -> DividerRowView(row)
        is PhoneUseRequestRow -> PhoneUseRequestRowView(
            row = row,
            serviceOn = serviceOn,
            onAllow = if (serviceOn) {
                { onAllowWithPermission(row.requestId) }
            } else {
                {}
            },
            onDecline = { phoneUse.onDecline(row.requestId) },
            onOpenStatus = phoneUse.onOpenStatus,
        )
        is PhoneUseAskRow -> PhoneUseAskRowView(row)
        is PhoneUseStepRow -> PhoneUseStepRowView(row)
        is PhoneUseStepGroupRow -> PhoneUseStepGroupRowView(row, expanded, onToggle)
        is PhoneUseStartedRow -> PhoneUseStartedRowView(row)
        is PhoneUseEndedRow -> PhoneUseEndedRowView(row)
    }
}

/** A13.6: the running session's banner above the input, with the task and a Stop. */
@Composable
private fun PhoneUseBanner(task: String, onStop: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("chat-phone-banner"),
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("The steward is using your phone", style = MaterialTheme.typography.labelMedium)
                if (task.isNotBlank()) {
                    Text(task, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            androidx.compose.material3.TextButton(
                onClick = onStop,
                modifier = Modifier.testTag("chat-phone-stop"),
            ) { Text("Stop") }
        }
    }
}

/** A13.6: the app's confirm-gate question as a card in Chat (the notification and its Stop stay). */
@Composable
private fun ConfirmGateCard(request: com.example.phonesteward.screen.ConfirmRequest, onAnswer: (Boolean) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("chat-confirm-card"),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // A15.11: the allow-for-this-task question asks about the app, in its own words.
            val allowForTask = request.action == "allow_app"
            Text(if (allowForTask) "Allow this app for this task?" else "Allow this tap?", style = MaterialTheme.typography.titleSmall)
            Text(
                if (allowForTask) {
                    "The steward wants to use “${request.label.take(60)}” for this task. It is not on your allow list. " +
                        "Only this task may use it."
                } else {
                    "The steward wants to tap “${request.label.take(60)}”. It matched “${request.word}”. Nothing happens until you answer."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.TextButton(onClick = { onAnswer(true) }, modifier = Modifier.testTag("chat-confirm-allow")) {
                    Text(if (allowForTask) "Allow for this task" else "Allow")
                }
                androidx.compose.material3.TextButton(onClick = { onAnswer(false) }, modifier = Modifier.testTag("chat-confirm-deny")) {
                    Text(if (allowForTask) "Not now" else "Don't allow")
                }
            }
        }
    }
}

/** L4: a calm line above the message box when a message could not be sent. */
@Composable
private fun ChatNotice(text: String, onDismiss: () -> Unit, isError: Boolean = true) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag(if (isError) "chat-notice" else "chat-info"),
        shape = MaterialTheme.shapes.medium,
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
        }
    }
}

/** A15.5: insert dictated text where the cursor was, keeping the caret after it. */
private fun insertAtCursor(draft: TextFieldValue, insert: String): TextFieldValue {
    val start = draft.selection.min
    val end = draft.selection.max
    val text = draft.text.substring(0, start) + insert + draft.text.substring(end)
    return TextFieldValue(text, TextRange(start + insert.length))
}
