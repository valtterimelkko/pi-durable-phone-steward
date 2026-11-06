package com.example.phonesteward.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.phonesteward.logic.ActionResult
import com.example.phonesteward.logic.Onboarding
import com.example.phonesteward.ui.theme.Spacing
import com.example.phonesteward.ui.theme.StewardTheme

/** `d MMM HH:mm` in device local time (the Status tab's last worker run). */
internal fun formatStamp(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm"))

/** A flat, rounded card with a hairline border, as in the Pi Web UI. */
@Composable
fun StewardCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, StewardTheme.semantic.cardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        content()
    }
}

/** A titled section: a card with a title row and spaced content. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    StewardCard(modifier) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(key, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = Spacing.m))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * R1 layout fix: a permission row keeps its text and its button on separate
 * lines. The button never shares a width-constrained Row with the label, so
 * long labels wrap freely and the button keeps a minimum width — "Settings"
 * can never wrap one letter per line again. Asserted by PermissionRowLayoutTest.
 *
 * N3: the row presents one of three states — granted (green tick, "Allowed",
 * and no button at all: owner rule of 2026-10-08, a greyed button looked
 * dead), not needed (neutral label instead of the button), or action needed
 * (amber warning, enabled button).
 */
@Composable
fun PermissionRow(
    title: String,
    description: String,
    state: Onboarding.RowState,
    buttonText: String,
    onButtonClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val granted = state == Onboarding.RowState.GRANTED
    val notNeeded = state == Onboarding.RowState.NOT_NEEDED
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            StateIcon(state)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (!granted) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (granted) {
                Text(
                    "Allowed",
                    style = MaterialTheme.typography.labelMedium,
                    color = StewardTheme.semantic.success,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (notNeeded) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.padding(start = 36.dp),
            ) {
                Text(
                    "Not needed now",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.m, vertical = 6.dp),
                )
            }
        } else if (!granted) {
            FilledTonalButton(
                onClick = onButtonClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minWidth = 120.dp)
                    .heightIn(min = Spacing.touch),
            ) {
                Text(buttonText, maxLines = 1)
            }
        }
    }
}

@Composable
private fun StateIcon(state: Onboarding.RowState) {
    val (icon, tint, description) = when (state) {
        Onboarding.RowState.GRANTED ->
            Triple(Icons.Filled.CheckCircle, StewardTheme.semantic.success, "Allowed")
        Onboarding.RowState.NOT_NEEDED ->
            Triple(Icons.Filled.Info, MaterialTheme.colorScheme.outline, "Not needed now")
        Onboarding.RowState.ACTION_NEEDED ->
            Triple(Icons.Filled.Warning, StewardTheme.semantic.warning, "Action needed")
    }
    Icon(
        icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .size(24.dp)
            .semantics { contentDescription = description },
    )
}

/** A centred "nothing here" panel: an icon, a title and one explanatory line. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/**
 * N16: the outcome of the owner's last action, shown on the screen where the
 * action happened. Success is green, "nothing happened" is neutral, an error
 * is red. It stays until dismissed or replaced by the next result.
 */
@Composable
fun ResultCard(result: ActionResult, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val (container, onContainer, icon) = when (result.kind) {
        ActionResult.Kind.SUCCESS -> Triple(
            StewardTheme.semantic.successContainer, StewardTheme.semantic.onSuccessContainer, Icons.Filled.CheckCircle,
        )
        ActionResult.Kind.INFO -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface, Icons.Filled.Info,
        )
        ActionResult.Kind.ERROR -> Triple(
            MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, Icons.Filled.Warning,
        )
    }
    Surface(
        modifier = modifier.fillMaxWidth().testTag("result-card"),
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = onContainer,
    ) {
        Row(
            Modifier.padding(start = Spacing.l, top = Spacing.xs, bottom = Spacing.xs, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(
                result.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = Spacing.s),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss")
            }
        }
    }
}

/** A quiet inline message (rules saved, a problem with the form, a server error). */
@Composable
fun NoticeCard(text: String, isError: Boolean = false, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(Spacing.m))
    }
}

/**
 * The one in-app confirmation : a title, a plain sentence, Cancel and a confirm button. Used where Android has no
 * dialog of its own (files to the Steward trash, Empty Steward trash, Start fresh, a pay-as-you-go model).
 *
 * The question draws in the app's own window, as plain composition over a dark area — not in a dialog window. A dialog
 * window added while another popup or dialog window had just been removed was occasionally lost on Test Lab devices
 * (FlowB1 and FlowC1: the question never appeared and the tap was swallowed), and as composition this
 * cannot happen: the question arrives with the next frame or not at all. Back and a tap on the dark area answer no.
 */
@Composable
fun ConfirmDialog(confirm: AppViewModel.PendingConfirm, onAnswer: (Boolean) -> Unit) {
    androidx.activity.compose.BackHandler { onAnswer(false) }
    // Nothing behind the card can be typed into either: the keyboard goes away while the question is up.
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.compose.runtime.LaunchedEffect(confirm) { focus.clearFocus() }
    val scrimInteraction = androidx.compose.runtime.remember { MutableInteractionSource() }
    val cardInteraction = androidx.compose.runtime.remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = scrimInteraction,
                indication = null,
            ) { onAnswer(false) }
            .testTag("confirm-dialog"),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 24.dp,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .clickable(
                    interactionSource = cardInteraction,
                    indication = null,
                ) {},
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(confirm.title, style = MaterialTheme.typography.headlineSmall)
                Text(confirm.body, style = MaterialTheme.typography.bodyMedium)
                Row(
                    Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = { onAnswer(false) },
                        modifier = Modifier.heightIn(min = Spacing.touch).testTag("confirm-no"),
                    ) { Text("Cancel") }
                    TextButton(
                        onClick = { onAnswer(true) },
                        modifier = Modifier.heightIn(min = Spacing.touch).testTag("confirm-yes"),
                    ) {
                        Text(
                            confirm.confirmLabel,
                            color = if (confirm.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
