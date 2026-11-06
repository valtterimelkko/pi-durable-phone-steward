package com.example.phonesteward.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.phonesteward.logic.chat.ChatFormat
import com.example.phonesteward.logic.chat.MessageRow
import com.example.phonesteward.logic.chat.NoteRow
import com.example.phonesteward.logic.chat.PhoneResultRow
import com.example.phonesteward.logic.chat.ProposalRow
import com.example.phonesteward.logic.chat.RunRow
import com.example.phonesteward.logic.chat.ToolCallView
import com.example.phonesteward.logic.chat.ToolCardRow
import com.example.phonesteward.logic.chat.ToolGroupRow
import com.example.phonesteward.logic.chat.ToolState
import com.example.phonesteward.logic.chat.WorkingRow
import com.example.phonesteward.ui.theme.Spacing
import com.example.phonesteward.ui.theme.StewardTheme

private val MonoSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

@Composable
private fun mutedColor() = MaterialTheme.colorScheme.outline

@Composable
private fun Time(atMs: Long, nowMs: Long, modifier: Modifier = Modifier) {
    val text = ChatFormat.time(atMs, nowMs)
    if (text.isNotEmpty()) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = mutedColor(), modifier = modifier)
    }
}

/** Owner messages are rounded grey bubbles on the right; steward messages are plain Markdown text.
 * A15.6: [actions] is the round's Read aloud slot, drawn under a finished steward round. */
@Composable
fun MessageRowView(row: MessageRow, nowMs: Long, actions: (@Composable () -> Unit)? = null) {
    if (row.fromOwner) {
        val bubble = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Color(0xFFF3F4F6) else Color(0xFF2C3440)
        Column(Modifier.fillMaxWidth().testTag("chat-owner-message"), horizontalAlignment = Alignment.End) {
            Surface(
                color = bubble,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                SelectionContainer {
                    Text(
                        row.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                    )
                }
            }
            Time(row.atMs, nowMs, Modifier.padding(top = 2.dp, end = 4.dp))
        }
    } else {
        Column(Modifier.fillMaxWidth().testTag("chat-steward-message")) {
            SelectionContainer { MarkdownContent(row.text, Modifier.padding(horizontal = 4.dp)) }
            // A15.10: the model that answered this round, and the honest warning when it is not
            // the one chosen in Settings — the model is never a surprise.
            if (row.roundEnd && row.roundModelLabel != null) {
                Text(
                    row.roundModelLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedColor(),
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp).testTag("chat-round-model"),
                )
            }
            row.roundModelWarning?.let { warning ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp).testTag("chat-round-model-warning"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = StewardTheme.semantic.warning, modifier = Modifier.size(14.dp))
                    Text(warning, style = MaterialTheme.typography.labelSmall, color = StewardTheme.semantic.warning)
                }
            }
            Row(Modifier.padding(start = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                // A15.7: a finished steward round carries its copy button on its last message.
                if (row.roundEnd && row.roundText != null) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    androidx.compose.material3.IconButton(
                        onClick = { com.example.phonesteward.ui.ClipboardCopy.copy(context, "steward reply", row.roundText.orEmpty()) },
                        modifier = Modifier.size(30.dp).testTag("chat-copy-round"),
                    ) {
                        Icon(
                            com.example.phonesteward.ui.theme.StewardIcons.Copy,
                            contentDescription = "Copy reply",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                // A15.6: the voice feature's Read aloud sits beside Copy.
                actions?.invoke()
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Time(row.atMs, nowMs)
            }
        }
    }
}

@Composable
fun NoteRowView(row: NoteRow) {
    Text(
        row.text,
        style = MaterialTheme.typography.labelSmall,
        color = mutedColor(),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/** A10.9: the line a fresh start draws; the chat above it stays, the steward's memory restarts below it. */
@Composable
fun DividerRowView(row: com.example.phonesteward.logic.chat.DividerRow) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("chat-divider"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            row.text,
            style = MaterialTheme.typography.labelMedium,
            color = mutedColor(),
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
fun PhoneResultRowView(row: PhoneResultRow) {
    val color = if (row.isError) MaterialTheme.colorScheme.error else mutedColor()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("chat-phone-result"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Phone, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(row.text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun RunRowView(row: RunRow, nowMs: Long) {
    val time = ChatFormat.time(row.atMs, nowMs)
    val reason = when (row.reason) {
        "owner" -> "your message"
        "weekly_check" -> "weekly check"
        "phone_result" -> "phone result"
        "photo_window" -> "photo rule"
        else -> null
    }
    val text = if (row.started) {
        listOfNotNull("Run started", reason, time.ifEmpty { null }).joinToString(" · ")
    } else {
        listOfNotNull("Run ended", row.durationMs?.let { "took ${ChatFormat.duration(it)}" }, time.ifEmpty { null }).joinToString(" · ")
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("chat-run-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(6.dp).background(mutedColor(), CircleShape))
        Text(text, style = MaterialTheme.typography.labelSmall, color = mutedColor())
    }
}

@Composable
fun ProposalRowView(row: ProposalRow, onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, StewardTheme.semantic.cardBorder),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat-proposal-link"),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f)) {
                Text("New proposal", style = MaterialTheme.typography.labelSmall, color = mutedColor())
                Text(row.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = mutedColor(), modifier = Modifier.size(20.dp))
        }
    }
}

/** The quiet "Working…" line while a run is open. */
@Composable
fun WorkingRowView(row: WorkingRow) {
    val pulse by rememberInfiniteTransition(label = "working").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp).testTag("chat-working"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).alpha(pulse).background(MaterialTheme.colorScheme.primary, CircleShape))
        Text(row.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- tool calls ---------------------------------------------------------------------

private fun iconFor(name: String): ImageVector = when (name) {
    "phone_storage_overview", "phone_app_usage", "phone_media_index" -> Icons.Filled.Phone
    "propose_cleanup" -> Icons.Filled.Create
    else -> Icons.Filled.Search
}

/** Medium: "Checked 3 things ▸", expanding to the cards. */
@Composable
fun ToolGroupRowView(row: ToolGroupRow, expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("chat-tool-group")) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clickable(onClick = onToggle)
                .padding(horizontal = 4.dp)
                .semantics { contentDescription = "${row.summary}, ${if (expanded) "expanded" else "collapsed"}" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val parts = row.summary.split(" · ", limit = 2)
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    append(parts[0])
                    if (parts.size > 1) {
                        append(" · ")
                        withStyle(androidx.compose.ui.text.SpanStyle(color = MaterialTheme.colorScheme.error)) { append(parts[1]) }
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.running) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = mutedColor())
            }
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = mutedColor(),
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, StewardTheme.semantic.cardBorder),
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Column {
                    row.calls.forEachIndexed { index, call ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        ToolCallItem(call, startExpanded = false, showTiming = false)
                    }
                }
            }
        }
    }
}

/** High: every tool call as its own card, expanded, with arguments, result and duration. */
@Composable
fun ToolCardRowView(row: ToolCardRow) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (row.call.state == ToolState.Error) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else StewardTheme.semantic.cardBorder),
        modifier = Modifier.fillMaxWidth().testTag("chat-tool-card"),
    ) {
        ToolCallItem(row.call, startExpanded = true, showTiming = true)
    }
}

@Composable
private fun ToolCallItem(call: ToolCallView, startExpanded: Boolean, showTiming: Boolean) {
    var open by remember(call.name, startExpanded) { mutableStateOf(startExpanded) }
    val failed = call.state == ToolState.Error
    Column(Modifier.fillMaxWidth().testTag("chat-tool-row")) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable { open = !open }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (call.state) {
                ToolState.Running -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.primary)
                ToolState.Error -> Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(15.dp))
                ToolState.Done -> Icon(iconFor(call.name), null, tint = mutedColor(), modifier = Modifier.size(15.dp))
            }
            Text(
                call.label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (!open && call.argsSummary != null && call.argsSummary != "no arguments") {
                Text(
                    call.argsSummary,
                    style = MonoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            val (status, statusColor) = when (call.state) {
                ToolState.Running -> "Running…" to StewardTheme.semantic.warning
                ToolState.Error -> "Error" to MaterialTheme.colorScheme.error
                ToolState.Done -> (if (call.durationMs != null) "Done · ${ChatFormat.duration(call.durationMs)}" else "Done") to StewardTheme.semantic.success
            }
            Text(status, style = MaterialTheme.typography.labelSmall, color = statusColor, maxLines = 1)
            Icon(
                if (open) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = mutedColor(),
                modifier = Modifier.size(18.dp),
            )
        }
        if (open) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DetailBlock("Input", call.argsSummary ?: "not recorded", false)
                if (call.state != ToolState.Running) {
                    DetailBlock(if (failed) "Error" else "Result", call.resultSummary ?: "not recorded", failed)
                }
                if (showTiming && call.durationMs != null) {
                    Text("Took ${ChatFormat.duration(call.durationMs)}", style = MaterialTheme.typography.labelSmall, color = mutedColor())
                }
            }
        }
    }
}

@Composable
private fun DetailBlock(title: String, body: String, isError: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) MaterialTheme.colorScheme.error else StewardTheme.semantic.success.takeIf { title == "Result" } ?: mutedColor(),
        )
        Text(
            body,
            style = MonoSmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- phone use from Chat (A13.6) ----------------------------------------------------

/** A13.6: the request card. Live Allow / Not now while pending; its outcome once answered. */
@Composable
fun PhoneUseRequestRowView(
    row: com.example.phonesteward.logic.chat.PhoneUseRequestRow,
    serviceOn: Boolean,
    onAllow: () -> Unit,
    onDecline: () -> Unit,
    onOpenStatus: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, StewardTheme.semantic.cardBorder),
        modifier = Modifier.fillMaxWidth().testTag("chat-phone-request"),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Use the phone", style = MaterialTheme.typography.labelSmall, color = mutedColor())
            Text(row.task, style = MaterialTheme.typography.titleSmall)
            if (row.why.isNotBlank()) {
                Text(row.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (row.open) {
                Button(
                    onClick = onAllow,
                    enabled = serviceOn,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touch).testTag("chat-phone-allow"),
                ) { Text("Allow") }
                if (!serviceOn) {
                    Text(
                        "The Accessibility service is off, so the steward cannot use the phone yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("chat-phone-allow-reason"),
                    )
                    Text(
                        "Go to Status",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.touch)
                            .clickable(onClick = onOpenStatus)
                            .padding(vertical = 8.dp)
                            .testTag("chat-phone-goto-status"),
                    )
                }
                FilledTonalButton(
                    onClick = onDecline,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touch).testTag("chat-phone-notnow"),
                ) { Text("Not now") }
            } else {
                Text(
                    when (row.status) {
                        "allowed" -> "You allowed this."
                        "declined" -> "Not now — nothing was started."
                        // N40: the server has said the request is no longer pending (expired or superseded).
                        "expired", "superseded" -> "This request has expired."
                        else -> "No longer pending."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor(),
                    modifier = Modifier.testTag("chat-phone-outcome"),
                )
            }
        }
    }
}

/** A13.6: the highlighted question mid-session; the owner answers by typing normally. */
@Composable
fun PhoneUseAskRowView(row: com.example.phonesteward.logic.chat.PhoneUseAskRow) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().testTag("chat-phone-ask"),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("The steward asks", style = MaterialTheme.typography.labelSmall)
            Text(row.question, style = MaterialTheme.typography.titleSmall)
            Text("Type your answer below.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A13.6, High: one answered screen step. */
@Composable
fun PhoneUseStepRowView(row: com.example.phonesteward.logic.chat.PhoneUseStepRow) {
    val failed = row.outcome != null && row.outcome != "ok"
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("chat-phone-step"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.Phone,
            contentDescription = null,
            tint = if (failed) MaterialTheme.colorScheme.error else mutedColor(),
            modifier = Modifier.size(14.dp),
        )
        Text(
            row.summary,
            style = MaterialTheme.typography.labelMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A13.6, Medium: one session's steps collapsed to one line; expanding shows them. */
@Composable
fun PhoneUseStepGroupRowView(
    row: com.example.phonesteward.logic.chat.PhoneUseStepGroupRow,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag("chat-phone-step-group"),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Phone, contentDescription = null, tint = mutedColor(), modifier = Modifier.size(14.dp))
                Text(
                    "Used the phone · ${row.count} ${if (row.count == 1) "step" else "steps"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = mutedColor(),
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                row.calls.forEach { PhoneUseStepRowView(it) }
            }
        }
    }
}

/** A13.6: the session's quiet start line and its short result line. */
@Composable
fun PhoneUseStartedRowView(row: com.example.phonesteward.logic.chat.PhoneUseStartedRow) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("chat-phone-started"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Phone, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
        // A15.10: the session's model is named on its first line.
        val line = "Phone session started: ${row.task}" + (row.modelLabel?.let { " · $it" } ?: "")
        Text(line, style = MaterialTheme.typography.labelMedium, color = mutedColor())
    }
}

@Composable
fun PhoneUseEndedRowView(row: com.example.phonesteward.logic.chat.PhoneUseEndedRow) {
    // The ended row is the session's one end line: the result words and, when the server sent one, the summary under them.
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("chat-phone-ended")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Phone, contentDescription = null, tint = mutedColor(), modifier = Modifier.size(14.dp))
            Text(row.text, style = MaterialTheme.typography.labelMedium, color = mutedColor())
        }
        if (!row.summary.isNullOrBlank()) {
            Text(
                row.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 22.dp, top = 2.dp).testTag("chat-phone-ended-summary"),
            )
        }
    }
}
