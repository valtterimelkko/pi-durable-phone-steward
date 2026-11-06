package com.example.phonesteward.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.phonesteward.android.AppGraph
import com.example.phonesteward.logic.obs.Diagnostics
import com.example.phonesteward.logic.shizuku.RestrictedSettings
import com.example.phonesteward.logic.shizuku.ShizukuText
import com.example.phonesteward.ui.KeyValue
import com.example.phonesteward.ui.NoticeCard
import com.example.phonesteward.ui.SectionCard
import com.example.phonesteward.ui.theme.Spacing

/**
 * The words Android needs the owner to know about sideloaded accessibility services, in the order that works (A12.2):
 * switch it on first, Android may answer "Restricted setting", then allow it in App info, or with Shizuku where the phone
 * has no three-dot menu.
 */
const val RESTRICTED_SETTINGS_STEPS =
    "Android blocks this for apps installed outside Google Play until you allow it once.\n" +
        "1. Open Accessibility, choose Phone Steward under Installed apps and switch it on. " +
        "Android may answer “Restricted setting”.\n" +
        "2. Then open Phone Steward's app info, tap the three-dot menu at the top right, choose Allow restricted settings " +
        "and switch it on again.\n" +
        "Some OEM builds, e.g. OxygenOS, have no three-dot menu: use Allow with Shizuku."

/** What the Allow with Shizuku part of the card shows (A12.1): the offer, a working flag and the last result. */
data class RestrictedUi(
    val offer: RestrictedSettings.Offer,
    val working: Boolean = false,
    val result: RestrictedSettings.Outcome? = null,
    /** A short line when opening Shizuku did not work, so a tap is never silent. */
    val notice: String? = null,
)

/**
 * Status card: turn the screen service on, give the steward one task, watch it, stop it.
 * Stateful wrapper around [PhoneUseCard]; the service, the session state and the confirm
 * question come from the process-wide objects.
 */
@Composable
fun PhoneUseSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ui by PhoneUseState.state.collectAsState()
    val asking by ConfirmBroker.asking.collectAsState()
    var serviceOn by remember { mutableStateOf(StewardAccessibilityService.isEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) serviceOn = StewardAccessibilityService.isEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // A12: the Shizuku state is read again on resume and whenever Shizuku starts, stops or answers its dialog.
    val graph = remember { AppGraph.get(context.applicationContext) }
    val restrictedSettings = remember { RestrictedSettings(graph.shizuku, context.packageName, Build.VERSION.SDK_INT) }
    var offer by remember { mutableStateOf(restrictedSettings.offer(serviceOn)) }
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RestrictedSettings.Outcome?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(serviceOn) { offer = restrictedSettings.offer(serviceOn) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) offer = restrictedSettings.offer(StewardAccessibilityService.isEnabled(context))
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        val listener = graph.shizuku.addChangeListener {
            offer = restrictedSettings.offer(StewardAccessibilityService.isEnabled(context))
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            listener.close()
        }
    }
    val scope = rememberCoroutineScope()
    PhoneUseCard(
        serviceOn = serviceOn,
        ui = ui,
        asking = asking,
        restricted = RestrictedUi(offer, working, result, notice),
        onAllowWithShizuku = {
            if (!working) {
                working = true
                scope.launch {
                    // The command waits for Shizuku: never on the main thread.
                    val outcome = withContext(Dispatchers.IO) { restrictedSettings.allow() }
                    result = outcome
                    working = false
                    offer = restrictedSettings.offer(StewardAccessibilityService.isEnabled(context))
                }
            }
        },
        onShizukuAction = { action ->
            notice = null
            fun launched(intent: Intent?, site: String) {
                val ok = intent != null && launchOrReport(context, intent, site)
                if (!ok) {
                    if (intent == null) Diagnostics.error(site, IllegalStateException("no launch intent for Shizuku"))
                    notice = RestrictedSettings.COULD_NOT_OPEN
                }
            }
            when (action) {
                ShizukuText.Action.GET_SHIZUKU -> launched(graph.androidShizuku.getIntent(), "phone_use.shizuku_get")
                ShizukuText.Action.OPEN_SHIZUKU -> launched(graph.androidShizuku.openIntent(), "phone_use.shizuku_open")
                ShizukuText.Action.ALLOW_SHIZUKU -> graph.shizuku.requestPermission()
                ShizukuText.Action.CHECK_AGAIN -> offer = restrictedSettings.offer(serviceOn)
            }
        },
        onStop = { LiveSessionService.stop(context) },
        onOpenAppInfo = { openAppInfo(context) },
        onOpenAccessibility = { openAccessibility(context) },
        onAnswer = { allowed, question -> ConfirmBroker.answer(allowed, question) },
        modifier = modifier,
    )
}

/** True when the launch worked; a failure is reported to Diagnostics and the caller shows a line. */
private fun launchOrReport(context: Context, intent: Intent, site: String): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: Exception) {
    Diagnostics.error(site, e)
    false
}

private fun openAppInfo(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun openAccessibility(context: Context) {
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Status card, A13.6: turn the screen service on (with the A12 help), watch a running session, stop it.
 * There is no task box: the owner asks in Chat, and the session starts from the request card's Allow.
 */
@Composable
fun PhoneUseCard(
    serviceOn: Boolean,
    ui: PhoneUseUi,
    asking: ConfirmRequest?,
    restricted: RestrictedUi,
    onStop: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onAllowWithShizuku: () -> Unit,
    onShizukuAction: (ShizukuText.Action) -> Unit,
    onAnswer: (Boolean, ConfirmRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = ui is PhoneUseUi.Starting || ui is PhoneUseUi.Running
    SectionCard("Phone use", modifier) {
        Text(
            "The steward can tap and type on your phone for one task at a time. It works only in apps you allow in Rules, " +
                "asks you before anything that deletes, pays or sends, never opens banking, mail, photo or password apps, " +
                "and stops after 40 steps or 15 minutes. The notification has a Stop button.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KeyValue("Accessibility service", if (serviceOn) "On" else "Off")
        if (!serviceOn) {
            NoticeCard(RESTRICTED_SETTINGS_STEPS, modifier = Modifier.testTag("phone-use-restricted-note"))
            FilledTonalButton(
                onClick = onOpenAppInfo,
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touch).testTag("phone-use-app-info"),
            ) { Text("Open app info") }
            FilledTonalButton(
                onClick = onOpenAccessibility,
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touch).testTag("phone-use-accessibility"),
            ) { Text("Open Accessibility settings") }
            when (val offer = restricted.offer) {
                RestrictedSettings.Offer.Hidden -> Unit
                RestrictedSettings.Offer.Button ->
                    if (restricted.working) {
                        Text("Allowing…", modifier = Modifier.testTag("phone-use-allow-working"))
                    } else {
                        FilledTonalButton(
                            onClick = onAllowWithShizuku,
                            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touch).testTag("phone-use-allow-shizuku"),
                        ) { Text("Allow with Shizuku") }
                    }
                is RestrictedSettings.Offer.NotReady -> {
                    Text(
                        offer.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("phone-use-shizuku-note"),
                    )
                    FilledTonalButton(
                        onClick = { onShizukuAction(offer.action) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touch).testTag("phone-use-shizuku-action"),
                    ) { Text(ShizukuText.label(offer.action), maxLines = 1) }
                }
            }
            restricted.notice?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("phone-use-shizuku-notice"),
                )
            }
            restricted.result?.let {
                Text(
                    RestrictedSettings.line(it),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("phone-use-allow-result"),
                )
            }
        }
        when (ui) {
            is PhoneUseUi.Starting -> Text("Starting…", modifier = Modifier.testTag("phone-use-status"))
            is PhoneUseUi.Running -> {
                Text(
                    if (ui.waitingFor != null) "Waiting for you: “${ui.waitingFor}”" else "Running, step ${ui.steps}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag("phone-use-status"),
                )
                Text(ui.task, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is PhoneUseUi.Ended -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(PhoneUseState.endText(ui.reason), modifier = Modifier.testTag("phone-use-ended"))
                ui.summary?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            PhoneUseUi.Idle -> Unit
        }
        if (active) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touch).testTag("phone-use-stop"),
            ) { Text("Stop") }
        } else {
            Text(
                "Ask the steward in Chat to use your phone.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("phone-use-ask-in-chat"),
            )
        }
    }
    val drawn = asking
    if (drawn != null) {
        AlertDialog(
            // The dialog answers the question it was drawn for (H2): a tap after this question
            // timed out and a newer one arrived must answer nothing.
            onDismissRequest = { onAnswer(false, drawn) },
            // A15.11: the allow-for-this-task question has its own words.
            title = { Text(if (drawn.action == "allow_app") "Allow this app for this task?" else "Allow this tap?") },
            text = {
                Text(
                    if (drawn.action == "allow_app") {
                        "The steward wants to use “${drawn.label}” for this task. It is not on your allow list."
                    } else {
                        "The steward wants to tap “${drawn.label}”. It matched “${drawn.word}”."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { onAnswer(true, drawn) }, modifier = Modifier.testTag("phone-use-allow")) {
                    Text(if (drawn.action == "allow_app") "Allow for this task" else "Allow")
                }
            },
            dismissButton = {
                TextButton(onClick = { onAnswer(false, drawn) }, modifier = Modifier.testTag("phone-use-deny")) {
                    Text(if (drawn.action == "allow_app") "Not now" else "Don't allow")
                }
            },
            modifier = Modifier.testTag("phone-use-confirm"),
        )
    }
}
