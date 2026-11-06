package com.example.phonesteward.screen

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guarded step loop: fakes for the server, the screen and the owner. */
class SessionRunnerTest {

    private val own = "com.example.phonesteward"

    private class FakeChannel : LiveChannel {
        val steps = LinkedBlockingQueue<LiveNext>()
        val answers = mutableListOf<Pair<String, StepAnswerOut>>()
        val stops = mutableListOf<String>()
        var startedTask: String? = null
        var nextCalls = 0
        var failNext: Throwable? = null
        override fun start(task: String): String { startedTask = task; return "ls-1" }
        override fun next(sessionId: String, waitSeconds: Int): LiveNext {
            nextCalls++
            failNext?.let { throw it }
            return steps.poll(30, TimeUnit.MILLISECONDS) ?: LiveNext.Idle
        }
        @Synchronized override fun answer(sessionId: String, stepId: String, answer: StepAnswerOut) { answers += stepId to answer }
        @Synchronized override fun stop(sessionId: String, reason: String) { stops += reason }
        fun step(id: String, tool: String, vararg args: Pair<String, String>) { steps.add(LiveNext.Step(id, tool, args.toMap())) }
        fun last() = answers.last().second
    }

    private class FakeDriver : ScreenDriver {
        var fg: Foreground? = Foreground("com.android.settings", "Settings")
        var tree: Serialised? = null
        val nodes = mutableMapOf<String, NodeFacts>()
        val performed = mutableListOf<String>()
        var actResult = ActResult(true, null)
        var afterAct: (() -> Unit)? = null
        override fun foreground() = fg
        override fun observe() = tree
        override fun facts(nodeId: String) = nodes[nodeId]
        private fun act(what: String): ActResult { performed += what; afterAct?.invoke(); return actResult }
        override fun tap(nodeId: String) = act("tap $nodeId")
        override fun scroll(nodeId: String?, direction: String) = act("scroll $nodeId $direction")
        override fun type(nodeId: String, text: String) = act("type $nodeId $text")
        override fun back() = act("back")
        override fun home() = act("home")
        override fun openApp(packageName: String) = act("open $packageName")
    }

    private class FakeConfirmer(
        var answer: Boolean = false,
        var outcome: ConfirmOutcome? = null,
    ) : OwnerConfirmer {
        val asked = mutableListOf<ConfirmRequest>()
        override fun confirm(request: ConfirmRequest): Boolean { asked += request; return answer }
        override fun confirmOutcome(request: ConfirmRequest): ConfirmOutcome {
            asked += request
            return outcome ?: if (answer) ConfirmOutcome.ALLOWED else ConfirmOutcome.DENIED
        }
    }

    private val ch = FakeChannel()
    private val drv = FakeDriver()
    private val conf = FakeConfirmer()
    private var clock = 0L
    private val budget = SessionBudget(clock = { clock })

    private fun runner(
        policy: GuardPolicy = GuardPolicy(DefaultGuards.lists(), own),
        appLabel: (String) -> String? = { null },
    ) = SessionRunner(ch, drv, policy, conf, budget, listener = SessionListener.None, appLabel = appLabel, pause = {})

    private fun tree(pkg: String = "com.android.settings") =
        Serialised(ScreenTreeModel(pkg, "Settings", listOf(ScreenNodeModel("n1", "button", "Clear cache", null, true, false, false, Bounds(0, 0, 1, 1))), false), mapOf("n1" to "h"))

    private fun node(label: String, editable: Boolean = false, password: Boolean = false) =
        NodeFacts(labels = listOf(label), text = label, editable = editable, password = password)

    @Test
    fun `starts the session with the owner's task and ends when the server says it ended`() {
        ch.steps.add(LiveNext.Ended("finished", "done"))
        val end = runner().run("clear one cache")
        assertEquals("clear one cache", ch.startedTask)
        assertEquals("server_ended", end.reason)
        assertTrue(ch.stops.isEmpty())
    }

    /** A13.2: an Allow started the session on the server; the app joins it, never calling start again. */
    @Test
    fun `runFromSession joins the server's session without calling start`() {
        ch.steps.add(LiveNext.Ended("finished", "all done"))
        val end = runner().runFromSession("ls-77", "clear one cache")
        assertNull("the start route was not called", ch.startedTask)
        assertEquals("server_ended", end.reason)
        assertEquals("all done", end.summary)
    }

    @Test
    fun `runFromSession honours stop and the listener`() {
        ch.steps.add(LiveNext.Ended("finished", null))
        val listener = object : SessionListener {
            val seen = mutableListOf<String>()
            override fun onStarted(sessionId: String, task: String) { seen += sessionId }
            override fun onEnded(end: SessionEnd) { seen += "ended:${end.reason}" }
        }
        SessionRunner(ch, drv, GuardPolicy(DefaultGuards.lists(), own), conf, budget, listener = listener, pause = {})
            .runFromSession("ls-9", "t")
        assertEquals(listOf("ls-9", "ended:server_ended"), listener.seen)
    }

    @Test
    fun `observe returns the tree of an allowed app`() {
        drv.tree = tree()
        ch.step("s1", "screen_observe"); ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertEquals("ok", ch.last().outcome)
        assertEquals("n1", ch.last().tree!!.nodes[0].id)
    }

    @Test
    fun `observe of the steward's own screen is allowed`() {
        drv.fg = Foreground(own, "MainActivity"); drv.tree = tree(own)
        ch.step("s1", "screen_observe"); ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertEquals("ok", ch.last().outcome)
    }

    @Test
    fun `observe of an app that is not listed is refused and the session goes on`() {
        drv.fg = Foreground("com.example.unknown", "Main"); drv.tree = tree("com.example.unknown")
        ch.step("s1", "screen_observe"); ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertEquals("refused", ch.last().outcome)
        assertNull(ch.last().tree)
        assertTrue(ch.last().detail!!.contains("com.example.unknown"))
        assertTrue(ch.stops.isEmpty())
    }

    @Test
    fun `entering a denied app ends the session`() {
        drv.fg = Foreground("com.google.android.gm", "Inbox"); drv.tree = tree("com.google.android.gm")
        ch.step("s1", "screen_observe")
        val end = runner().run("t")
        assertEquals("denied", end.reason)
        assertEquals("refused", ch.last().outcome)
        assertEquals(listOf("denied"), ch.stops)
    }

    @Test
    fun `a lock screen settings page ends the session`() {
        drv.fg = Foreground("com.android.settings", "com.android.settings.password.ChooseLockGeneric"); drv.tree = tree()
        ch.step("s1", "screen_observe")
        val end = runner().run("t")
        assertEquals("denied", end.reason)
        assertNull(ch.last().tree)
    }

    @Test
    fun `a tap on an ordinary node is performed and answered with the node text`() {
        drv.nodes["n1"] = node("Storage")
        ch.step("s1", "screen_act", "action" to "tap", "nodeId" to "n1"); ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertEquals(listOf("tap n1"), drv.performed)
        assertEquals("ok", ch.last().outcome)
        assertEquals("Storage", ch.last().nodeText)
        assertTrue(conf.asked.isEmpty())
    }

    @Test
    fun `a tap on clear cache is performed without asking`() {
        drv.nodes["n1"] = node("Clear cache")
        ch.step("s1", "screen_act", "action" to "tap", "nodeId" to "n1"); ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertEquals(listOf("tap n1"), drv.performed)
        assertTrue(conf.asked.isEmpty())
    }

    @Test
    fun `a committing word pauses for the owner, and a no stops the tap`() {
        drv.nodes["n1"] = node("Uninstall")
        conf.answer = false
        ch.step("s1", "screen_act", "action" to "tap", "nodeId" to "n1"); ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertEquals(1, conf.asked.size)
        assertEquals("uninstall", conf.asked[0].word)
        assertEquals("Uninstall", conf.asked[0].label)
        assertTrue(drv.performed.isEmpty())
        assertEquals("refused", ch.last().outcome)
        assertTrue(ch.last().detail!!.contains("owner"))
    }

    @Test
    fun `a committing word with the owner's yes is performed`() {
        drv.nodes["n1"] = node("Delete")
        conf.answer = true
        ch.step("s1", "screen_act", "action" to "tap", "nodeId" to "n1"); ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertEquals(listOf("tap n1"), drv.performed)
        assertEquals("ok", ch.last().outcome)
    }

    @Test
    fun `clear data is never performed and the owner is not even asked`() {
        drv.nodes["n1"] = node("Clear data")
        conf.answer = true
        ch.step("s1", "screen_act", "action" to "tap", "nodeId" to "n1"); ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertTrue(conf.asked.isEmpty())
        assertTrue(drv.performed.isEmpty())
        assertEquals("refused", ch.last().outcome)
        assertTrue(ch.last().detail!!.contains("never"))
    }

    @Test
    fun `an unknown node id fails the step without acting`() {
        ch.step("s1", "screen_act", "action" to "tap", "nodeId" to "n99"); ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertTrue(drv.performed.isEmpty())
        assertEquals("failed", ch.last().outcome)
    }

    @Test
    fun `acting on the steward's own screen is refused`() {
        drv.fg = Foreground(own, "MainActivity"); drv.nodes["n1"] = node("Approve")
        ch.step("s1", "screen_act", "action" to "tap", "nodeId" to "n1"); ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertTrue(drv.performed.isEmpty())
        assertEquals("refused", ch.last().outcome)
        assertTrue(ch.last().detail!!.contains("own"))
    }

    @Test
    fun `acting in an app that is not listed is refused`() {
        drv.fg = Foreground("com.example.unknown", "Main"); drv.nodes["n1"] = node("Open")
        ch.step("s1", "screen_act", "action" to "tap", "nodeId" to "n1"); ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertTrue(drv.performed.isEmpty())
        assertEquals("refused", ch.last().outcome)
    }

    @Test
    fun `never types into a password field`() {
        drv.nodes["n1"] = node("Password", editable = true, password = true)
        ch.step("s1", "screen_act", "action" to "type", "nodeId" to "n1", "text" to "hunter2"); ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertTrue(drv.performed.isEmpty())
        assertEquals("refused", ch.last().outcome)
        assertFalse(ch.last().detail!!.contains("hunter2"))
    }

    @Test
    fun `typing needs an editable field`() {
        drv.nodes["n1"] = node("Storage")
        ch.step("s1", "screen_act", "action" to "type", "nodeId" to "n1", "text" to "x"); ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertTrue(drv.performed.isEmpty())
        assertEquals("refused", ch.last().outcome)
    }

    @Test
    fun `typing into an editable field is performed`() {
        drv.nodes["n1"] = node("Search", editable = true)
        ch.step("s1", "screen_act", "action" to "type", "nodeId" to "n1", "text" to "storage"); ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertEquals(listOf("type n1 storage"), drv.performed)
        assertEquals("ok", ch.last().outcome)
    }

    @Test
    fun `back home and scroll need no node and are performed`() {
        ch.step("s1", "screen_act", "action" to "back")
        ch.step("s2", "screen_act", "action" to "home")
        ch.step("s3", "screen_act", "action" to "scroll", "direction" to "down")
        ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertEquals(listOf("back", "home", "scroll null down"), drv.performed)
    }

    @Test
    fun `open_app respects the allow and deny lists`() {
        ch.step("s1", "screen_act", "action" to "open_app", "packageName" to "com.google.android.gm")
        ch.step("s2", "screen_act", "action" to "open_app", "packageName" to "com.example.unknown")
        ch.step("s3", "screen_act", "action" to "open_app", "packageName" to "com.android.settings")
        ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertEquals(listOf("open com.android.settings"), drv.performed)
        assertEquals(listOf("refused", "refused", "ok"), ch.answers.map { it.second.outcome })
    }

    // ---- A15.11: allow an app for this task ----

    @Test
    fun `an unlisted app is offered to the owner and, on Allow, observed and used for this session`() {
        drv.fg = Foreground("com.google.android.gms", "Main"); drv.tree = tree("com.google.android.gms")
        drv.nodes["n1"] = node("Open the list")
        conf.answer = true
        ch.step("s1", "screen_observe")
        ch.step("s2", "screen_act", "action" to "tap", "nodeId" to "n1")
        ch.steps.add(LiveNext.Ended(null, null))
        runner(appLabel = { "Google Play services" }).run("t")
        assertEquals(listOf("ok", "ok"), ch.answers.map { it.second.outcome })
        val ask = conf.asked.single()
        assertEquals("allow_app", ask.action)
        assertEquals("Google Play services", ask.label)
        assertEquals("com.google.android.gms", ask.word)
        assertEquals(120_000L, ask.timeoutMs)
        assertEquals(listOf("tap n1"), drv.performed)
    }

    @Test
    fun `when the owner says not now the step is refused and the detail says the owner did not allow it`() {
        drv.fg = Foreground("com.google.android.gms", "Main"); drv.tree = tree("com.google.android.gms")
        conf.answer = false
        ch.step("s1", "screen_observe"); ch.steps.add(LiveNext.Ended(null, null))
        runner(appLabel = { "Google Play services" }).run("t")
        assertEquals("refused", ch.last().outcome)
        assertTrue(ch.last().detail!!.contains("did not allow"))
        assertNull(ch.last().tree)
    }

    @Test
    fun `an unanswered question refuses and the detail says there was no answer in two minutes`() {
        drv.fg = Foreground("com.google.android.gms", "Main"); drv.tree = tree("com.google.android.gms")
        conf.outcome = ConfirmOutcome.NO_ANSWER
        ch.step("s1", "screen_observe"); ch.steps.add(LiveNext.Ended(null, null))
        runner(appLabel = { "Google Play services" }).run("t")
        assertEquals("refused", ch.last().outcome)
        assertTrue(ch.last().detail!!.contains("did not answer in two minutes"))
    }

    @Test
    fun `an always-denied app is never offered and the session ends`() {
        drv.fg = Foreground("com.mybank.mobile", "Accounts"); drv.tree = tree("com.mybank.mobile")
        conf.answer = true
        ch.step("s1", "screen_observe")
        val end = runner(appLabel = { "Invented Bank" }).run("t")
        assertEquals("denied", end.reason)
        assertTrue("the owner is never asked about a denied app", conf.asked.isEmpty())
        assertEquals("refused", ch.last().outcome)
    }

    @Test
    fun `the steward's own screen is never offered for this task`() {
        drv.fg = Foreground(own, "MainActivity"); drv.tree = tree(own)
        drv.nodes["n1"] = node("Approve")
        conf.answer = true
        ch.step("s1", "screen_act", "action" to "tap", "nodeId" to "n1")
        ch.steps.add(LiveNext.Ended(null, null))
        runner(appLabel = { "Phone Steward" }).run("t")
        assertTrue("the steward's own screen is never offered", conf.asked.isEmpty())
        assertEquals("refused", ch.last().outcome)
        assertTrue(drv.performed.isEmpty())
    }

    @Test
    fun `a sensitive screen in an unlisted app is never offered and ends the session`() {
        drv.fg = Foreground("com.example.unlisted", "com.example.unlisted.ChooseLockPattern"); drv.tree = tree("com.example.unlisted")
        conf.answer = true
        ch.step("s1", "screen_observe")
        val end = runner(appLabel = { "Invented Unlisted" }).run("t")
        assertEquals("denied", end.reason)
        assertTrue(conf.asked.isEmpty())
    }

    @Test
    fun `open_app to an unlisted package is offered and opens on Allow`() {
        conf.answer = true
        ch.step("s1", "screen_act", "action" to "open_app", "packageName" to "com.google.android.gms")
        ch.steps.add(LiveNext.Ended(null, null))
        runner(appLabel = { "Google Play services" }).run("t")
        assertEquals(listOf("ok"), ch.answers.map { it.second.outcome })
        assertEquals(listOf("open com.google.android.gms"), drv.performed)
        assertEquals("allow_app", conf.asked.single().action)
    }

    @Test
    fun `a tap in an unlisted app is offered and performed on Allow`() {
        drv.nodes["n1"] = node("Open the list")
        conf.answer = true
        ch.step("s1", "screen_act", "action" to "tap", "nodeId" to "n1")
        ch.steps.add(LiveNext.Ended(null, null))
        runner(appLabel = { "Google Play services" }).run("t")
        assertEquals(listOf("tap n1"), drv.performed)
        assertEquals("ok", ch.last().outcome)
    }

    @Test
    fun `an unknown tool or action is refused`() {
        ch.step("s1", "screen_format"); ch.step("s2", "screen_act", "action" to "swipe_to_pay"); ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertEquals(listOf("refused", "refused"), ch.answers.map { it.second.outcome })
        assertTrue(drv.performed.isEmpty())
    }

    @Test
    fun `an action that lands in a denied app ends the session`() {
        drv.nodes["n1"] = node("Open mail")
        drv.afterAct = { drv.fg = Foreground("com.google.android.gm", "Inbox") }
        ch.step("s1", "screen_act", "action" to "tap", "nodeId" to "n1"); ch.step("s2", "screen_observe")
        val end = runner().run("t")
        assertEquals("denied", end.reason)
        assertEquals(listOf("denied"), ch.stops)
        assertEquals(1, ch.answers.size)
    }

    @Test
    fun `the 41st step ends the session`() {
        drv.tree = tree()
        repeat(41) { ch.step("s$it", "screen_observe") }
        val end = runner().run("t")
        assertEquals("budget", end.reason)
        assertEquals(40, ch.answers.size)
        assertEquals(listOf("budget"), ch.stops)
    }

    @Test
    fun `fifteen minutes end the session even while idle (A13_4)`() {
        drv.tree = tree()
        ch.step("s1", "screen_observe")
        // The clock jumps after the first step.
        drv.afterAct = null
        val r = runner()
        val t = Thread { clock = 0L }
        t.start(); t.join()
        val answersBefore = ch.answers.size
        ch.steps.add(LiveNext.Idle)
        clock = 15 * 60_000L + 1
        val end = r.run("t")
        assertEquals("budget", end.reason)
        assertEquals(answersBefore, ch.answers.size)
    }

    @Test
    fun `Stop ends the session at once and tells the server`() {
        val r = runner()
        var end: SessionEnd? = null
        val t = Thread { end = r.run("t") }
        t.start()
        Thread.sleep(150)
        r.stop()
        t.join(5_000)
        assertFalse(t.isAlive)
        assertEquals("stopped", end!!.reason)
        assertEquals(listOf("stopped"), ch.stops)
    }

    @Test
    fun `the server is told from the runner's own thread, never the caller's (Android refuses network on the main thread)`() {
        val callers = java.util.Collections.synchronizedList(mutableListOf<Thread>())
        val recording = object : LiveChannel by ch {
            override fun stop(sessionId: String, reason: String) { callers += Thread.currentThread(); ch.stop(sessionId, reason) }
        }
        val r = SessionRunner(recording, drv, GuardPolicy(DefaultGuards.lists(), own), conf, budget, pause = {})
        val t = Thread { r.run("t") }
        t.start()
        Thread.sleep(150)
        val caller = Thread.currentThread()
        r.stop()
        t.join(5_000)
        assertEquals(listOf("stopped"), ch.stops)
        assertTrue(callers.none { it === caller })
    }

    @Test
    fun `Stop while waiting for the owner declines the pending tap`() {
        drv.nodes["n1"] = node("Delete")
        val blocking = object : OwnerConfirmer {
            override fun confirm(request: ConfirmRequest): Boolean { Thread.sleep(300); return true }
        }
        val r = SessionRunner(ch, drv, GuardPolicy(DefaultGuards.lists(), own), blocking, budget, pause = {})
        ch.step("s1", "screen_act", "action" to "tap", "nodeId" to "n1")
        var end: SessionEnd? = null
        val t = Thread { end = r.run("t") }
        t.start()
        Thread.sleep(100)
        r.stop()
        t.join(5_000)
        assertEquals("stopped", end!!.reason)
        assertTrue("a stopped session must not tap", drv.performed.isEmpty())
    }

    @Test
    fun `a window change into a denied app during the long poll ends the session`() {
        val r = runner()
        var end: SessionEnd? = null
        val t = Thread { end = r.run("t") }
        t.start()
        Thread.sleep(150)
        r.onForeground("com.paypal.android.p2pmobile", "Home")
        t.join(5_000)
        assertFalse(t.isAlive)
        assertEquals("denied", end!!.reason)
        assertEquals(listOf("denied"), ch.stops)
    }

    @Test
    fun `a window change to an allowed app changes nothing`() {
        val r = runner()
        var end: SessionEnd? = null
        val t = Thread { end = r.run("t") }
        t.start()
        Thread.sleep(100)
        r.onForeground("com.android.settings", "Settings")
        Thread.sleep(100)
        assertTrue(t.isAlive)
        r.stop(); t.join(5_000)
        assertEquals("stopped", end!!.reason)
    }

    @Test
    fun `three failed polls in a row end the session with an error`() {
        ch.failNext = RuntimeException("offline")
        val end = runner().run("t")
        assertEquals("error", end.reason)
        assertEquals(3, ch.nextCalls)
        assertEquals(listOf("error"), ch.stops)
    }

    @Test
    fun `a server that says the session is gone ends it quietly`() {
        ch.failNext = SessionGoneException()
        val end = runner().run("t")
        assertEquals("server_ended", end.reason)
        assertTrue(ch.stops.isEmpty())
    }

    @Test
    fun `idle polls do not use up steps`() {
        repeat(100) { ch.steps.add(LiveNext.Idle) }
        ch.steps.add(LiveNext.Ended(null, null))
        val end = runner().run("t")
        assertEquals("server_ended", end.reason)
        assertNotNull(end)
        assertEquals(0, ch.answers.size)
    }

    // ---- N33: the dialog around a bare OK ----

    @Test
    fun `an OK under an uninstall question pauses for the owner naming uninstall`() {
        drv.nodes["n1"] = NodeFacts(listOf("OK"), "OK", editable = false, password = false, windowTexts = listOf("Uninstall this app?", "Cancel"))
        conf.answer = false
        ch.step("s1", "screen_act", "action" to "tap", "nodeId" to "n1"); ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertEquals(1, conf.asked.size)
        assertEquals("uninstall", conf.asked[0].word)
        assertTrue(drv.performed.isEmpty())
    }

    @Test
    fun `an OK on a plain information dialog is performed without asking`() {
        drv.nodes["n1"] = NodeFacts(listOf("OK"), "OK", editable = false, password = false, windowTexts = listOf("Backup finished"))
        ch.step("s1", "screen_act", "action" to "tap", "nodeId" to "n1"); ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertEquals(listOf("tap n1"), drv.performed)
        assertTrue(conf.asked.isEmpty())
    }

    @Test
    fun `an OK under a wipe question is refused even without asking`() {
        drv.nodes["n1"] = NodeFacts(listOf("OK"), "OK", editable = false, password = false, windowTexts = listOf("Clear data?"))
        ch.step("s1", "screen_act", "action" to "tap", "nodeId" to "n1"); ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertEquals("refused", ch.last().outcome)
        assertTrue(drv.performed.isEmpty())
        assertTrue(conf.asked.isEmpty())
    }

    @Test
    fun `a Clear chat button pauses for the owner`() {
        drv.nodes["n1"] = node("Clear chat")
        ch.step("s1", "screen_act", "action" to "tap", "nodeId" to "n1"); ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertEquals("clear", conf.asked.single().word)
    }

    // ---- N34: tapped text leaves the phone without codes ----

    @Test
    fun `the tapped node's text is sent without a verification code`() {
        drv.nodes["n1"] = node("Your verification code is 48213")
        ch.step("s1", "screen_act", "action" to "tap", "nodeId" to "n1"); ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertFalse(ch.last().nodeText!!.contains("48213"))
        assertTrue(ch.last().nodeText!!.contains("••••"))
    }

    @Test
    fun `the detail is sent without a verification code too`() {
        drv.nodes["n1"] = node("Storage")
        drv.actResult = ActResult(false, "could not press: code 123456 changed")
        ch.step("s1", "screen_act", "action" to "tap", "nodeId" to "n1"); ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertFalse(ch.last().detail!!.contains("123456"))
    }

    @Test
    fun `a refusal after the owner's no does not leak the code in the label`() {
        drv.nodes["n1"] = node("Send code 654321")
        conf.answer = false
        ch.step("s1", "screen_act", "action" to "tap", "nodeId" to "n1"); ch.steps.add(LiveNext.Ended(null, null))
        runner().run("t")
        assertFalse(ch.last().detail!!.contains("654321"))
        assertFalse(ch.last().nodeText!!.contains("654321"))
    }

    // ---- N36: the listener hears what the steward is doing ----

    @Test
    fun `the listener is told each action in words`() {
        val heard = mutableListOf<String>()
        val listener = object : SessionListener { override fun onAction(text: String) { heard += text } }
        drv.tree = tree()
        drv.nodes["n1"] = node("Storage")
        ch.step("s1", "screen_observe")
        ch.step("s2", "screen_act", "action" to "tap", "nodeId" to "n1")
        ch.step("s3", "screen_act", "action" to "back")
        ch.steps.add(LiveNext.Ended(null, null))
        SessionRunner(ch, drv, GuardPolicy(DefaultGuards.lists(), own), conf, budget, listener = listener, pause = {}).run("t")
        assertEquals(listOf("Looking at the screen", "Tapping “Storage”", "Going back"), heard)
    }
}
