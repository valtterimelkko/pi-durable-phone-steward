package com.example.phonesteward.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * [ScreenDriver] over the accessibility service. Node ids are valid for the latest observation
 * only; a node that has since changed fails the step so the model observes again.
 */
class AndroidScreenDriver(
    private val service: AccessibilityService,
    private val context: Context,
    private val foregroundProvider: () -> Foreground?,
    private val settleMs: Long = 500,
) : ScreenDriver {

    @Volatile private var handles: Map<String, Any?> = emptyMap()

    override fun foreground(): Foreground? = foregroundProvider()

    override fun observe(): Serialised? {
        val root = service.rootInActiveWindow ?: return null
        val fg = foreground()
        val raw = convert(root, intArrayOf(0), 0)
        val s = TreeSerialiser.serialise(raw, root.packageName?.toString() ?: fg?.pkg ?: "unknown", fg?.activity)
        handles = s.handles
        return s
    }

    private fun convert(n: AccessibilityNodeInfo, count: IntArray, depth: Int): RawNode {
        count[0]++
        val rect = Rect().also { n.getBoundsInScreen(it) }
        val children = if (depth >= TreeSerialiser.MAX_DEPTH || count[0] > MAX_CONVERT) emptyList() else (0 until n.childCount).mapNotNull { i ->
            if (count[0] > MAX_CONVERT) null else n.getChild(i)?.let { convert(it, count, depth + 1) }
        }
        val text = n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.hintText?.toString()
        return RawNode(
            className = n.className?.toString(),
            text = if (n.isPassword) null else text,
            desc = if (n.isPassword) null else n.contentDescription?.toString(),
            clickable = n.isClickable,
            editable = n.isEditable,
            password = n.isPassword,
            visible = n.isVisibleToUser,
            scrollable = n.isScrollable,
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            children = children,
            handle = n,
        )
    }

    private fun handle(nodeId: String): AccessibilityNodeInfo? = handles[nodeId] as? AccessibilityNodeInfo

    override fun facts(nodeId: String): NodeFacts? {
        val node = handle(nodeId) ?: return null
        if (!node.refresh()) return null
        val labels = LinkedHashSet<String>()
        fun add(n: AccessibilityNodeInfo) {
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { labels += it }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { labels += it }
        }
        add(node)
        // A tap on a label inside a clickable row presses the row: judge the row's words too.
        clickableAncestor(node)?.takeIf { it != node }?.let { row ->
            var budget = 24
            val queue = ArrayDeque<AccessibilityNodeInfo>().also { it.add(row) }
            while (queue.isNotEmpty() && budget-- > 0) {
                val n = queue.removeFirst()
                if (n.isPassword) continue
                add(n)
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            }
        }
        val own = node.text?.toString()?.takeIf { it.isNotBlank() } ?: node.contentDescription?.toString()
        return NodeFacts(labels.toList(), own, node.isEditable, node.isPassword, windowTexts(node, labels))
    }

    /**
     * The other visible texts of the node's window, for judging a bare "OK" or "Yes" by its dialog (N33). A dialog is
     * its own window, so the window root holds the title and message. Bounded; password fields are skipped.
     */
    private fun windowTexts(node: AccessibilityNodeInfo, own: Set<String>): List<String> {
        var root: AccessibilityNodeInfo = node
        var hops = 0
        while (hops++ < 40) root = root.parent ?: break
        val out = LinkedHashSet<String>()
        var budget = 120
        val queue = ArrayDeque<AccessibilityNodeInfo>().also { it.add(root) }
        while (queue.isNotEmpty() && budget-- > 0 && out.size < 40) {
            val n = queue.removeFirst()
            if (n.isPassword) continue
            n.text?.toString()?.takeIf { it.isNotBlank() && it !in own }?.let { out += it.take(300) }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() && it !in own }?.let { out += it.take(300) }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return out.toList()
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops++ < 8) {
            if (n.isClickable) return n
            n = n.parent
        }
        return null
    }

    override fun tap(nodeId: String): ActResult {
        val node = handle(nodeId) ?: return ActResult(false, "node $nodeId is not on the latest screen")
        if (!node.refresh()) return ActResult(false, "the screen changed; observe again")
        val target = clickableAncestor(node)
        val ok = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true || gestureTap(node)
        settle()
        return if (ok) ActResult(true) else ActResult(false, "the tap did not register")
    }

    private fun gestureTap(node: AccessibilityNodeInfo): Boolean {
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        return service.dispatchGesture(gesture, null, null)
    }

    override fun scroll(nodeId: String?, direction: String): ActResult {
        val forward = direction == "down" || direction == "right"
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val start = nodeId?.let { handle(it) } ?: service.rootInActiveWindow
        val target = start?.let { scrollableFrom(it) } ?: return ActResult(false, "nothing on this screen scrolls")
        val ok = target.performAction(action)
        settle()
        return if (ok) ActResult(true) else ActResult(false, "it cannot scroll further that way")
    }

    private fun scrollableFrom(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops++ < 8) {
            if (n.isScrollable) return n
            n = n.parent
        }
        // Otherwise the first scrollable below.
        val queue = ArrayDeque<AccessibilityNodeInfo>().also { it.add(node) }
        var budget = 200
        while (queue.isNotEmpty() && budget-- > 0) {
            val c = queue.removeFirst()
            if (c.isScrollable) return c
            for (i in 0 until c.childCount) c.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    override fun type(nodeId: String, text: String): ActResult {
        val node = handle(nodeId) ?: return ActResult(false, "node $nodeId is not on the latest screen")
        if (!node.refresh()) return ActResult(false, "the screen changed; observe again")
        if (node.isPassword) return ActResult(false, "password fields are never typed into")
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        settle()
        return if (ok) ActResult(true) else ActResult(false, "the field did not take the text")
    }

    override fun back(): ActResult = global(AccessibilityService.GLOBAL_ACTION_BACK)

    override fun home(): ActResult = global(AccessibilityService.GLOBAL_ACTION_HOME)

    private fun global(action: Int): ActResult {
        val ok = service.performGlobalAction(action)
        settle()
        return if (ok) ActResult(true) else ActResult(false, "Android refused the action")
    }

    override fun openApp(packageName: String): ActResult {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActResult(false, "$packageName has no launcher screen or is not installed")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            val end = System.currentTimeMillis() + OPEN_WAIT_MS
            while (System.currentTimeMillis() < end) {
                if (foreground()?.pkg == packageName) {
                    settle()
                    return ActResult(true)
                }
                Thread.sleep(150)
            }
            ActResult(false, "$packageName did not come to the front; observe to see where you are")
        } catch (t: Exception) {
            ActResult(false, "could not open $packageName")
        }
    }

    private fun settle() {
        try {
            Thread.sleep(settleMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val MAX_CONVERT = 2500
        const val OPEN_WAIT_MS = 4_000L
    }
}
