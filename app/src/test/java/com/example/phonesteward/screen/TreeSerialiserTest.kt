package com.example.phonesteward.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeSerialiserTest {

    private fun node(
        text: String? = null,
        desc: String? = null,
        cls: String = "android.widget.TextView",
        clickable: Boolean = false,
        editable: Boolean = false,
        password: Boolean = false,
        visible: Boolean = true,
        scrollable: Boolean = false,
        bounds: Bounds = Bounds(0, 0, 100, 40),
        children: List<RawNode> = emptyList(),
        handle: Any? = null,
    ) = RawNode(cls, text, desc, clickable, editable, password, visible, scrollable, bounds, children, handle)

    @Test
    fun `numbers nodes in reading order and keeps the handles for acting`() {
        val root = node(cls = "android.widget.FrameLayout", children = listOf(
            node(text = "Storage", handle = "h1"),
            node(text = "Clear cache", cls = "android.widget.Button", clickable = true, handle = "h2"),
        ))
        val s = TreeSerialiser.serialise(root, "com.android.settings", "Settings")
        assertEquals(listOf("n1", "n2"), s.tree.nodes.map { it.id })
        assertEquals("button", s.tree.nodes[1].role)
        assertTrue(s.tree.nodes[1].clickable)
        assertEquals("h2", s.handles["n2"])
        assertEquals("com.android.settings", s.tree.pkg)
        assertEquals("Settings", s.tree.activity)
        assertFalse(s.tree.truncated)
    }

    @Test
    fun `drops password fields and everything under them`() {
        val root = node(children = listOf(
            node(text = "Sign in with"),
            node(text = "hunter2", cls = "android.widget.EditText", editable = true, password = true,
                children = listOf(node(text = "child-secret"))),
            node(text = "Forgot?"),
        ))
        val s = TreeSerialiser.serialise(root, "p", null)
        assertEquals(listOf("Sign in with", "Forgot?"), s.tree.nodes.map { it.text })
        assertFalse(s.tree.nodes.any { (it.text ?: "").contains("hunter2") || (it.text ?: "").contains("secret") })
    }

    @Test
    fun `skips invisible subtrees and empty decorative containers`() {
        val root = node(children = listOf(
            node(text = "Hidden", visible = false, children = listOf(node(text = "Hidden child"))),
            node(cls = "android.view.View"),
            node(text = "Shown"),
        ))
        val s = TreeSerialiser.serialise(root, "p", null)
        assertEquals(listOf("Shown"), s.tree.nodes.map { it.text })
    }

    @Test
    fun `text on a row inside a clickable parent is tappable through the parent`() {
        val root = node(children = listOf(
            node(cls = "android.view.ViewGroup", clickable = true, children = listOf(node(text = "Storage"), node(text = "12 GB used"))),
        ))
        val s = TreeSerialiser.serialise(root, "p", null)
        assertTrue(s.tree.nodes.all { it.clickable })
    }

    @Test
    fun `caps the node count and says it truncated`() {
        val many = List(TreeSerialiser.MAX_NODES + 30) { node(text = "row $it") }
        val s = TreeSerialiser.serialise(node(children = many), "p", null)
        assertEquals(TreeSerialiser.MAX_NODES, s.tree.nodes.size)
        assertTrue(s.tree.truncated)
        assertEquals(TreeSerialiser.MAX_NODES, s.handles.size)
    }

    @Test
    fun `caps text length`() {
        val s = TreeSerialiser.serialise(node(children = listOf(node(text = "x".repeat(1000)))), "p", null)
        assertEquals(TreeSerialiser.MAX_TEXT, s.tree.nodes[0].text!!.length)
    }

    @Test
    fun `does not follow a pathological depth`() {
        var inner = node(text = "bottom")
        repeat(500) { inner = node(children = listOf(inner)) }
        val s = TreeSerialiser.serialise(inner, "p", null)
        assertTrue(s.tree.truncated)
    }

    @Test
    fun `hides one-time codes next to the word code`() {
        val s = TreeSerialiser.serialise(node(children = listOf(
            node(text = "Your verification code is 482913"),
            node(text = "OTP: 7391"),
            node(text = "Storage used: 12345 MB"),
        )), "p", null)
        assertFalse(s.tree.nodes[0].text!!.contains("482913"))
        assertFalse(s.tree.nodes[1].text!!.contains("7391"))
        assertEquals("Storage used: 12345 MB", s.tree.nodes[2].text)
    }

    @Test
    fun `role comes from the class name`() {
        fun role(cls: String, clickable: Boolean = false, editable: Boolean = false) =
            TreeSerialiser.serialise(node(children = listOf(node(text = "t", cls = cls, clickable = clickable, editable = editable))), "p", null).tree.nodes[0].role
        assertEquals("button", role("android.widget.Button", clickable = true))
        assertEquals("input", role("android.widget.EditText", editable = true))
        assertEquals("toggle", role("android.widget.Switch", clickable = true))
        assertEquals("text", role("android.widget.TextView"))
        assertEquals("list", role("androidx.recyclerview.widget.RecyclerView"))
    }

    @Test
    fun `serialises to the protocol json`() {
        val s = TreeSerialiser.serialise(node(children = listOf(node(text = "Storage", clickable = true, bounds = Bounds(1, 2, 3, 4)))), "com.x", "Act")
        val json = s.tree.toJson().toString()
        assertTrue(json, json.contains("\"package\":\"com.x\""))
        assertTrue(json, json.contains("\"bounds\":[1,2,3,4]"))
        assertTrue(json, json.contains("\"truncated\":false"))
        assertNull(null)
    }
}
