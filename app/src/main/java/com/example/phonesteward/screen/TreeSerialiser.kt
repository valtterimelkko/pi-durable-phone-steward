package com.example.phonesteward.screen

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The screen tree of protocol A10.3 (`ScreenTree`), built on the phone from a platform-free
 * snapshot of the accessibility tree. Pure JVM so the limits are unit-tested: password fields
 * (and everything under them) are dropped, one-time codes are masked, the node count and the
 * text are capped, and the depth is bounded.
 */

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** One accessibility node, copied into plain values. [handle] is whatever the driver needs to act on it. */
data class RawNode(
    val className: String?,
    val text: String?,
    val desc: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val password: Boolean,
    val visible: Boolean = true,
    val scrollable: Boolean = false,
    val bounds: Bounds? = null,
    val children: List<RawNode> = emptyList(),
    val handle: Any? = null,
)

data class ScreenNodeModel(
    val id: String,
    val role: String,
    val text: String?,
    val desc: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val bounds: Bounds,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("role", role)
        text?.let { put("text", it) }
        desc?.let { put("desc", it) }
        put("clickable", clickable)
        put("editable", editable)
        if (scrollable) put("scrollable", true)
        put("bounds", JsonArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).map { JsonPrimitive(it) }))
    }
}

data class ScreenTreeModel(
    val pkg: String,
    val activity: String?,
    val nodes: List<ScreenNodeModel>,
    val truncated: Boolean,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("package", pkg)
        activity?.let { put("activity", it) }
        put("nodes", JsonArray(nodes.map { it.toJson() }))
        put("truncated", truncated)
    }
}

/** The tree plus the handles of its nodes, keyed by node id, for the actions that follow. */
class Serialised(val tree: ScreenTreeModel, val handles: Map<String, Any?>)

object TreeSerialiser {
    const val MAX_NODES = 120
    const val MAX_TEXT = 160
    const val MAX_DESC = 120
    const val MAX_DEPTH = 40
    private const val MAX_VISITED = 2000

    private val codeWords = Regex("""\b(code|otp|passcode|one-time|verification|verify|pin|tunnus|vahvistus|koodi|kod|code de|sms)\b""", RegexOption.IGNORE_CASE)
    private val digitRun = Regex("""\b\d{4,8}\b""")

    /** Mask a 4–8 digit run when the same text talks about a code. */
    fun redactCodes(text: String): String =
        if (codeWords.containsMatchIn(text)) digitRun.replace(text, "••••") else text

    private fun clean(value: String?, max: Int): String? {
        if (value == null) return null
        val t = redactCodes(value.replace(Regex("\\s+"), " ").trim())
        if (t.isEmpty()) return null
        return if (t.length > max) t.substring(0, max) else t
    }

    fun roleOf(node: RawNode): String {
        val cls = node.className.orEmpty()
        return when {
            node.editable || cls.endsWith("EditText") -> "input"
            cls.endsWith("Switch") || cls.endsWith("CheckBox") || cls.endsWith("RadioButton") || cls.endsWith("ToggleButton") -> "toggle"
            cls.endsWith("Button") -> "button"
            cls.endsWith("RecyclerView") || cls.endsWith("ListView") || cls.endsWith("ScrollView") || cls.endsWith("GridView") -> "list"
            cls.endsWith("ImageView") -> "image"
            cls.endsWith("TextView") -> "text"
            node.clickable -> "item"
            else -> "view"
        }
    }

    fun serialise(root: RawNode, pkg: String, activity: String?): Serialised {
        val nodes = ArrayList<ScreenNodeModel>()
        val handles = LinkedHashMap<String, Any?>()
        var truncated = false
        var visited = 0

        fun walk(node: RawNode, depth: Int, ancestorClickable: Boolean) {
            if (truncated) return
            visited++
            if (visited > MAX_VISITED || depth > MAX_DEPTH) {
                truncated = true
                return
            }
            // A password field is dropped with everything under it; so is an invisible subtree.
            if (node.password || !node.visible) return
            val text = clean(node.text, MAX_TEXT)
            val desc = clean(node.desc, MAX_DESC)
            val tappable = node.clickable || ancestorClickable
            val keep = text != null || desc != null || node.clickable || node.editable || node.scrollable
            if (keep) {
                if (nodes.size >= MAX_NODES) {
                    truncated = true
                    return
                }
                val id = "n${nodes.size + 1}"
                nodes += ScreenNodeModel(
                    id = id,
                    role = roleOf(node),
                    text = text,
                    desc = desc,
                    clickable = tappable,
                    editable = node.editable,
                    scrollable = node.scrollable,
                    bounds = node.bounds ?: Bounds(0, 0, 0, 0),
                )
                handles[id] = node.handle
            }
            for (child in node.children) walk(child, depth + 1, tappable)
        }

        walk(root, 0, false)
        return Serialised(ScreenTreeModel(pkg, activity, nodes, truncated), handles)
    }
}
