package com.example.phonesteward.screen

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** What the server says to a long-poll (protocol A10.3). */
sealed interface LiveNext {
    data class Step(val stepId: String, val tool: String, val args: Map<String, String>) : LiveNext
    data object Idle : LiveNext
    data class Ended(val reason: String?, val summary: String?) : LiveNext
}

data class StepAnswerOut(
    val outcome: String,
    val tree: ScreenTreeModel? = null,
    val detail: String? = null,
    val nodeText: String? = null,
)

/** The server session is gone (404 or 409): end quietly. */
class SessionGoneException : RuntimeException("the server no longer has this session")

interface LiveChannel {
    fun start(task: String): String
    fun next(sessionId: String, waitSeconds: Int): LiveNext
    fun answer(sessionId: String, stepId: String, answer: StepAnswerOut)
    fun stop(sessionId: String, reason: String)
}

data class Foreground(val pkg: String, val activity: String?)

data class ActResult(val ok: Boolean, val detail: String? = null)

/** What the runner needs to know about a node from the latest observation, before it acts on it. */
data class NodeFacts(
    val labels: List<String>,
    val text: String?,
    val editable: Boolean,
    val password: Boolean,
    /** The other visible texts of the node's window (a dialog's title and message): judged when the tap is a bare "OK". */
    val windowTexts: List<String> = emptyList(),
)

/** The screen, as the runner sees it. The Android implementation wraps the accessibility service. */
interface ScreenDriver {
    fun foreground(): Foreground?
    fun observe(): Serialised?
    fun facts(nodeId: String): NodeFacts?
    fun tap(nodeId: String): ActResult
    fun scroll(nodeId: String?, direction: String): ActResult
    fun type(nodeId: String, text: String): ActResult
    fun back(): ActResult
    fun home(): ActResult
    fun openApp(packageName: String): ActResult
}

/** One question to the owner. `action` is "tap" for the confirm gate or "allow_app" (A15.11). */
data class ConfirmRequest(
    val action: String,
    val label: String,
    val word: String,
    val pkg: String,
    /** A15.11: how long the owner has to answer; null uses the confirmer's own default. */
    val timeoutMs: Long? = null,
)

/** A15.11: what the owner did with a question — so a step's detail can say which happened. */
enum class ConfirmOutcome { ALLOWED, DENIED, NO_ANSWER }

interface OwnerConfirmer {
    /** Blocks until the owner answers. Anything but a clear yes (including a timeout) is a no. */
    fun confirm(request: ConfirmRequest): Boolean

    /**
     * A15.11: the same question, reported as allowed / denied / no answer. Implementations that
     * can tell a timeout from a "no" override this; the default folds both refusals into DENIED.
     */
    fun confirmOutcome(request: ConfirmRequest): ConfirmOutcome =
        if (confirm(request)) ConfirmOutcome.ALLOWED else ConfirmOutcome.DENIED
}

data class SessionEnd(val reason: String, val sessionId: String?, val summary: String? = null)

/**
 * The guarded step loop. It long-polls the server for the next step, checks every
 * step against the app's own guards, performs it, and answers. The model cannot change a guard.
 * Entering a denied app, Stop, 40 steps and 15 minutes each end the session at once.
 */
class SessionRunner(
    private val channel: LiveChannel,
    private val driver: ScreenDriver,
    private val policy: GuardPolicy,
    private val confirmer: OwnerConfirmer,
    private val budget: SessionBudget = SessionBudget(),
    private val listener: SessionListener = SessionListener.None,
    /** A15.11: the app's name for the allow-for-this-task question; the package id when null. */
    private val appLabel: (String) -> String? = { null },
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val pollSeconds: Int = 25,
) {
    private val stopRequested = AtomicBoolean(false)
    private val deniedHit = AtomicReference<String?>(null)
    private val currentSession = AtomicReference<String?>(null)
    private val finished = AtomicBoolean(false)

    // Telling the server runs on its own thread: stop() and onForeground() are called on the main thread,
    // where Android refuses network calls. done() waits for the message before the session thread ends.
    private val stopper = Executors.newSingleThreadExecutor { r -> Thread(r, "phone-use-stop").apply { isDaemon = true } }
    private val pendingStop = AtomicReference<Future<*>?>(null)

    /** The owner pressed Stop. Safe from any thread; the session ends at the next safe point. */
    fun stop() {
        stopRequested.set(true)
        // Tell the server straight away so it releases the model; the loop then exits on its next check.
        endOnServer("stopped")
    }

    /** A window-change event from the accessibility service: a denied app ends the session even mid-poll. */
    fun onForeground(pkg: String, activity: String?) {
        if (finished.get()) return
        if (policy.screenDenied(pkg, activity)) {
            deniedHit.compareAndSet(null, pkg)
            endOnServer("denied")
        }
    }

    private fun endOnServer(reason: String) {
        val id = currentSession.get() ?: return
        if (!finished.compareAndSet(false, true)) return
        runCatching { pendingStop.set(stopper.submit { runCatching { channel.stop(id, reason) } }) }
    }

    fun run(task: String): SessionEnd = try {
        loop(channel.start(task), task)
    } catch (t: Throwable) {
        listener.onEnded(SessionEnd("error", null))
        SessionEnd("error", null)
    }

    /** A13.2: the Allow answer already started the session on the server; the app joins it by id. */
    fun runFromSession(sessionId: String, task: String): SessionEnd = loop(sessionId, task)

    private fun loop(sessionId: String, task: String): SessionEnd {
        currentSession.set(sessionId)
        listener.onStarted(sessionId, task)
        var failedPolls = 0
        var steps = 0
        while (true) {
            userEnd(sessionId)?.let { return done(it) }
            budget.exhausted()?.let { return endBudget(sessionId) }
            val next = try {
                channel.next(sessionId, pollSeconds)
            } catch (_: SessionGoneException) {
                return done(SessionEnd("server_ended", sessionId))
            } catch (t: Throwable) {
                failedPolls++
                if (failedPolls >= 3) {
                    endOnServer("error")
                    return done(SessionEnd("error", sessionId))
                }
                pause(1000L * failedPolls)
                continue
            }
            failedPolls = 0
            userEnd(sessionId)?.let { return done(it) }
            when (next) {
                is LiveNext.Idle -> continue
                is LiveNext.Ended -> return done(SessionEnd("server_ended", sessionId, next.summary))
                is LiveNext.Step -> {
                    if (!budget.takeStep()) return endBudget(sessionId)
                    steps++
                    listener.onStep(steps, next.tool)
                    val answer = handle(next)
                    userEnd(sessionId)?.let { return done(it) }
                    deniedAfter()?.let {
                        post(sessionId, next.stepId, answer)
                        endOnServer("denied")
                        return done(SessionEnd("denied", sessionId))
                    }
                    if (!post(sessionId, next.stepId, answer)) return done(SessionEnd("server_ended", sessionId))
                    if (answer.deniedApp) {
                        endOnServer("denied")
                        return done(SessionEnd("denied", sessionId))
                    }
                }
            }
        }
    }

    private fun userEnd(sessionId: String): SessionEnd? = when {
        stopRequested.get() -> SessionEnd("stopped", sessionId)
        deniedHit.get() != null -> SessionEnd("denied", sessionId)
        else -> null
    }

    private fun endBudget(sessionId: String): SessionEnd {
        endOnServer("budget")
        return done(SessionEnd("budget", sessionId))
    }

    private fun done(end: SessionEnd): SessionEnd {
        // A user or guard ending must reach the server even if it happened before the loop started.
        if (end.reason == "stopped" || end.reason == "denied") endOnServer(end.reason)
        finished.set(true)
        pendingStop.get()?.let { runCatching { it.get(8, TimeUnit.SECONDS) } }
        stopper.shutdown()
        listener.onEnded(end)
        return end
    }

    private fun post(sessionId: String, stepId: String, answer: Answer): Boolean = try {
        channel.answer(sessionId, stepId, answer.out)
        true
    } catch (_: SessionGoneException) {
        false
    } catch (_: Throwable) {
        // The answer did not get through; the server's step timeout will tell the model to observe again.
        true
    }

    /** A foreground app that is denied after an action ends the session. */
    private fun deniedAfter(): String? {
        val fg = driver.foreground() ?: return null
        return if (policy.screenDenied(fg.pkg, fg.activity)) fg.pkg else null
    }

    private companion object {
        val NODE_ACTIONS = setOf("scroll", "tap", "type")

        /** A15.11: the owner has two minutes to answer the allow-for-this-task question. */
        const val ALLOW_FOR_TASK_TIMEOUT_MS = 2 * 60_000L
    }

    private class Answer(val out: StepAnswerOut, val deniedApp: Boolean = false)

    private fun refused(detail: String, deniedApp: Boolean = false) = Answer(StepAnswerOut("refused", detail = detail).redacted(), deniedApp)
    private fun failed(detail: String) = Answer(StepAnswerOut("failed", detail = detail).redacted())

    private fun handle(step: LiveNext.Step): Answer = when (step.tool) {
        "screen_observe" -> { listener.onAction(ActionText.describe(step.tool, step.args, null)); observe() }
        "screen_act" -> act(step.args)
        else -> refused("unknown tool ${step.tool.take(40)}")
    }

    private fun observe(): Answer {
        val fg = driver.foreground() ?: return failed("no window is on screen; try home or open_app")
        if (policy.screenDenied(fg.pkg, fg.activity)) {
            return refused("the screen belongs to ${fg.pkg}, which the steward may not use; the session ends", deniedApp = true)
        }
        if (!policy.mayObserve(fg.pkg)) {
            // A15.11: only the missing allow-list entry is offered to the owner; a refusal keeps
            // the old behaviour and the detail says whether the owner said no or did not answer.
            val outcome = offerAllowForThisTask(fg.pkg, sensitiveScreen = false)
            if (outcome != ConfirmOutcome.ALLOWED) {
                return refused(notListedDetail(fg.pkg, outcome, hint = "go back or home, or open an allowed app"))
            }
        }
        val tree = driver.observe() ?: return failed("the screen could not be read; try again")
        return Answer(StepAnswerOut("ok", tree = tree.tree))
    }

    /**
     * A15.11: ask the owner to allow one app for this task only. A yes adds the package to the
     * session-scoped list (never to the stored lists, and never over the deny list or the floor)
     * and the step runs; a no, or no answer inside two minutes, refuses as before.
     */
    private fun offerAllowForThisTask(pkg: String, sensitiveScreen: Boolean): ConfirmOutcome {
        if (!AllowForTask.offerable(pkg, policy.access(pkg), sensitiveScreen)) return ConfirmOutcome.DENIED
        val label = appLabel(pkg)?.takeIf { it.isNotBlank() } ?: pkg
        listener.onWaitingForOwner(label)
        val outcome = try {
            confirmer.confirmOutcome(
                ConfirmRequest(
                    action = "allow_app",
                    label = label,
                    word = pkg,
                    pkg = pkg,
                    timeoutMs = ALLOW_FOR_TASK_TIMEOUT_MS,
                ),
            )
        } catch (_: Throwable) {
            ConfirmOutcome.NO_ANSWER
        }
        listener.onOwnerAnswered(outcome == ConfirmOutcome.ALLOWED)
        if (outcome == ConfirmOutcome.ALLOWED) policy.allowForThisSession(pkg)
        return outcome
    }

    /** A15.11: the step's detail says which happened. */
    private fun notListedDetail(pkg: String, outcome: ConfirmOutcome, hint: String? = null): String {
        val why = when (outcome) {
            ConfirmOutcome.ALLOWED -> "the steward may use it for this task" // unreachable: an Allow proceeds
            ConfirmOutcome.DENIED -> "the owner did not allow it for this task"
            ConfirmOutcome.NO_ANSWER -> "the owner did not answer in two minutes"
        }
        return "$pkg is not on the allow list and $why" + (hint?.let { "; $it" } ?: "")
    }

    private fun act(args: Map<String, String>): Answer {
        val action = args["action"].orEmpty()
        val fg = driver.foreground()
        if (action !in NODE_ACTIONS) listener.onAction(ActionText.describe("screen_act", args, null))
        if (fg != null && policy.screenDenied(fg.pkg, fg.activity)) {
            return refused("the screen belongs to ${fg.pkg}, which the steward may not use; the session ends", deniedApp = true)
        }
        return when (action) {
            "back" -> result(driver.back(), null)
            "home" -> result(driver.home(), null)
            "open_app" -> {
                val pkg = args["packageName"].orEmpty()
                when (policy.access(pkg)) {
                    GuardPolicy.Access.ALLOWED -> result(driver.openApp(pkg), null)
                    GuardPolicy.Access.DENIED -> refused("$pkg is on the deny list")
                    GuardPolicy.Access.OWN_OBSERVE_ONLY -> refused("this is the steward's own app: it can be read but not used")
                    GuardPolicy.Access.NOT_LISTED -> {
                        // A15.11: the owner may allow an unlisted app for this task.
                        val outcome = offerAllowForThisTask(pkg, sensitiveScreen = false)
                        if (outcome == ConfirmOutcome.ALLOWED) {
                            result(driver.openApp(pkg), null)
                        } else {
                            refused(notListedDetail(pkg, outcome))
                        }
                    }
                }
            }
            "scroll", "tap", "type" -> nodeAction(action, args, fg)
            else -> refused("unknown action ${action.take(30)}")
        }
    }

    private fun nodeAction(action: String, args: Map<String, String>, fg: Foreground?): Answer {
        if (fg == null) return failed("no window is on screen")
        if (!policy.mayAct(fg.pkg)) {
            if (policy.access(fg.pkg) == GuardPolicy.Access.OWN_OBSERVE_ONLY) {
                return refused("this is the steward's own app: it can be read but not used")
            }
            // A15.11: an unlisted app may be allowed for this task, so the step can carry on.
            val outcome = offerAllowForThisTask(fg.pkg, sensitiveScreen = false)
            if (outcome != ConfirmOutcome.ALLOWED) {
                return refused(notListedDetail(fg.pkg, outcome))
            }
        }
        val direction = args["direction"].orEmpty()
        val nodeId = args["nodeId"]
        if (action == "scroll") {
            listener.onAction(ActionText.describe("screen_act", args, null))
            if (nodeId != null && driver.facts(nodeId) == null) return failed("node $nodeId is not on the latest screen; observe again")
            return result(driver.scroll(nodeId, direction), null)
        }
        if (nodeId == null) return failed("$action needs a nodeId")
        val facts = driver.facts(nodeId) ?: return failed("node $nodeId is not on the latest screen; observe again")
        listener.onAction(ActionText.describe("screen_act", args, facts.text ?: facts.labels.firstOrNull()))
        if (action == "type") {
            if (facts.password) return refused("this is a password field: the steward never types into those")
            if (!facts.editable) return refused("node $nodeId is not a text field")
            val text = args["text"].orEmpty()
            return result(driver.type(nodeId, text), facts.text)
        }
        // tap: the confirm gate.
        when (val verdict = ConfirmGate.classify(facts.labels, facts.windowTexts)) {
            is GateVerdict.Never -> return refused("\"${verdict.word}\" is never allowed: it would wipe data. Only Clear cache is permitted")
            is GateVerdict.Confirm -> {
                val label = facts.text ?: facts.labels.firstOrNull().orEmpty()
                listener.onWaitingForOwner(label)
                val yes = try {
                    confirmer.confirm(ConfirmRequest("tap", label, verdict.word, fg.pkg))
                } catch (_: Throwable) {
                    false
                }
                listener.onOwnerAnswered(yes)
                if (stopRequested.get() || !yes) {
                    return Answer(StepAnswerOut("refused", detail = "the owner did not allow tapping \"$label\"", nodeText = facts.text).redacted())
                }
            }
            GateVerdict.Allow -> Unit
        }
        return result(driver.tap(nodeId), facts.text)
    }

    private fun result(r: ActResult, nodeText: String?) =
        Answer(StepAnswerOut(if (r.ok) "ok" else "failed", detail = r.detail, nodeText = nodeText).redacted())

    /** Text read from the screen leaves the phone masked, by the same rule as the tree (N34). */
    private fun StepAnswerOut.redacted() = copy(
        detail = detail?.let { TreeSerialiser.redactCodes(it) },
        nodeText = nodeText?.let { TreeSerialiser.redactCodes(it) },
    )
}

interface SessionListener {
    fun onStarted(sessionId: String, task: String) {}
    fun onStep(count: Int, tool: String) {}

    /** A short, code-free description of what the steward is about to do ("Tapping “Storage”"). */
    fun onAction(text: String) {}
    fun onWaitingForOwner(label: String) {}
    fun onOwnerAnswered(allowed: Boolean) {}
    fun onEnded(end: SessionEnd) {}

    companion object {
        val None = object : SessionListener {}
    }
}
