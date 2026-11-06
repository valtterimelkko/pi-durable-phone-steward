package com.example.phonesteward.logic.exec

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.phonesteward.net.Proposal
import com.example.phonesteward.net.ProposalItem

/** N31 (A10.8): an uninstall proposal knows which apps are already gone. */
class RemovedAppsTest {

    private fun uninstall(id: String, pkg: String) =
        ProposalItem(id, "uninstall_app", buildJsonObject { put("packageName", pkg) })

    private fun proposal(vararg items: ProposalItem) =
        Proposal(proposalId = "pr-1", title = "Remove apps", items = items.toList())

    private val installed = setOf("com.invented.kept")
    private val isInstalled: (String) -> Boolean = { it in installed }

    @Test
    fun `only uninstall items whose package is gone are removed`() {
        val p = proposal(uninstall("u1", "com.invented.gone"), uninstall("u2", "com.invented.kept"))
        assertEquals(setOf("u1"), RemovedApps.removedItemIds(p, isInstalled))
    }

    @Test
    fun `other kinds are never marked removed`() {
        val p = proposal(
            ProposalItem("c1", "clear_app_cache", buildJsonObject { put("packageName", "com.invented.gone") }),
            ProposalItem("t1", "trash_media", buildJsonObject { put("mediaId", "external_primary:images:1") }),
        )
        assertTrue(RemovedApps.removedItemIds(p, isInstalled).isEmpty())
    }

    @Test
    fun `an item without a package name is not removed`() {
        val p = proposal(ProposalItem("u1", "uninstall_app", buildJsonObject { }))
        assertTrue(RemovedApps.removedItemIds(p, isInstalled).isEmpty())
    }

    @Test
    fun `a proposal of only gone uninstalls settles quietly`() {
        val p = proposal(uninstall("u1", "com.invented.gone"), uninstall("u2", "com.invented.gone2"))
        assertTrue(RemovedApps.settleable(p, isInstalled))
    }

    @Test
    fun `one app still installed means the owner decides`() {
        val p = proposal(uninstall("u1", "com.invented.gone"), uninstall("u2", "com.invented.kept"))
        assertFalse(RemovedApps.settleable(p, isInstalled))
    }

    @Test
    fun `a mixed proposal never settles by itself`() {
        val p = proposal(
            uninstall("u1", "com.invented.gone"),
            ProposalItem("c1", "clear_all_caches", buildJsonObject { }),
        )
        assertFalse(RemovedApps.settleable(p, isInstalled))
    }

    @Test
    fun `an empty proposal does not settle`() {
        assertFalse(RemovedApps.settleable(proposal(), isInstalled))
    }
}
