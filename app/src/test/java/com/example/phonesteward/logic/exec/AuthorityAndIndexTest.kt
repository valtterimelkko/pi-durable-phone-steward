package com.example.phonesteward.logic.exec

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.phonesteward.net.BucketStat
import com.example.phonesteward.net.DecisionCheckResponse
import com.example.phonesteward.net.MediaRef
import com.example.phonesteward.net.PhotoWindow
import com.example.phonesteward.net.RulesResponse
import com.example.phonesteward.net.TopLevelFile

private fun ref(
    id: String,
    path: String = "DCIM/Camera/",
    name: String = "$id.jpg",
    size: Long = 10,
    taken: Long? = null,
    added: Long? = null,
    favourite: Boolean = false,
    inKeep: Boolean = false,
) = MediaRef(
    mediaId = "external_primary:images:$id",
    relativePath = path,
    displayName = name,
    mimeType = "image/jpeg",
    sizeBytes = size,
    dateTakenMs = taken,
    dateAddedMs = added,
    isFavourite = favourite,
    inKeep = inKeep,
)

private fun row(
    id: String,
    path: String = "DCIM/Camera/",
    size: Long = 10,
    taken: Long? = null,
    added: Long? = null,
    favourite: Boolean = false,
    inKeep: Boolean = false,
    image: Boolean = true,
) = IndexedMedia(ref(id, path, size = size, taken = taken, added = added, favourite = favourite, inKeep = inKeep), isImage = image, isVideo = !image)

private fun decision(
    valid: Boolean = true,
    proposalId: String = "pr-1",
    approved: List<String> = listOf("it-1"),
    expiresAtMs: Long = 10_000L,
) = DecisionCheckResponse(valid, proposalId, approved, expiresAtMs)

class AuthorityGateTest {

    @Test
    fun `a valid decision approves exactly the intersection`() {
        val approved = AuthorityGate.approvedItems(
            decision(approved = listOf("it-1", "it-2")),
            proposalId = "pr-1",
            nowMs = 5_000,
            wanted = listOf("it-2", "it-3"),
        )
        assertEquals(setOf("it-2"), approved)
    }

    @Test
    fun `an invalid decision approves nothing`() {
        val approved = AuthorityGate.approvedItems(
            decision(valid = false, approved = listOf("it-1")),
            "pr-1", 5_000, listOf("it-1"),
        )
        assertTrue(approved.isEmpty())
    }

    @Test
    fun `a decision for another proposal approves nothing`() {
        val approved = AuthorityGate.approvedItems(
            decision(proposalId = "pr-OTHER", approved = listOf("it-1")),
            "pr-1", 5_000, listOf("it-1"),
        )
        assertTrue(approved.isEmpty())
    }

    @Test
    fun `an expired decision approves nothing`() {
        val approved = AuthorityGate.approvedItems(
            decision(expiresAtMs = 1_000),
            "pr-1", 5_000, listOf("it-1"),
        )
        assertTrue(approved.isEmpty())
    }

    @Test
    fun `rule authority needs enabled, autoMode and the same ruleVersion`() {
        fun rules(enabled: Boolean, auto: Boolean, version: Int) = RulesResponse(
            ruleVersion = version,
            photoWindow = PhotoWindow(enabled = enabled, autoMode = auto),
        )
        assertTrue(AuthorityGate.ruleAuthorises(rules(true, true, 3), 3))
        assertFalse("rule disabled", AuthorityGate.ruleAuthorises(rules(false, true, 3), 3))
        assertFalse("auto mode off", AuthorityGate.ruleAuthorises(rules(true, false, 3), 3))
        assertFalse("version moved on", AuthorityGate.ruleAuthorises(rules(true, true, 4), 3))
    }
}

class InKeepTest {

    @Test
    fun `any path segment equal to Keep protects, ignoring case`() {
        assertTrue(InKeep.forPath("Pictures/Keep/"))
        assertTrue(InKeep.forPath("DCIM/keep/"))
        assertTrue(InKeep.forPath("KEEP/holiday/"))
        assertFalse(InKeep.forPath("DCIM/Camera/"))
        assertFalse(InKeep.forPath("Pictures/Keeper/")) // segment must equal, not start with
        assertFalse(InKeep.forPath(null))
        assertFalse(InKeep.forPath(""))
    }
}

class ProtectionCheckTest {

    @Test
    fun `favourite or Keep protects on the live row`() {
        assertTrue(ProtectionCheck.isProtected(LiveMediaRow(isFavourite = true, inKeep = false)))
        assertTrue(ProtectionCheck.isProtected(LiveMediaRow(isFavourite = false, inKeep = true)))
        assertFalse(ProtectionCheck.isProtected(LiveMediaRow(isFavourite = false, inKeep = false)))
    }
}

class MediaIndexBuilderTest {

    private val zone = ZoneId.of("Europe/London")
    private val now = 1_800_000_000_000L

    @Test
    fun `buckets are counted only inside the rule scope`() {
        val scope = listOf("DCIM/Camera/")
        val rows = listOf(
            row("a", path = "DCIM/Camera/", taken = now - 150 * 24L * 3600 * 1000), // over 4m
            row("b", path = "Movies/TV/", taken = now - 150 * 24L * 3600 * 1000), // L14: unscoped
        )
        val summary = MediaIndexBuilder.summary(scope, rows, emptyList(), now, zone)
        val camera = summary.folders.single { it.folderKey == "DCIM/Camera" }
        val tv = summary.folders.single { it.folderKey == "Movies/TV" }
        assertEquals(1, camera.buckets!!.over4m!!.count)
        assertNull("folders outside the scope carry no buckets (R1/L14)", tv.buckets)
        // Both folders still report their bytes and counts.
        assertEquals(10, camera.bytes)
        assertEquals(10, tv.bytes)
    }

    @Test
    fun `buckets land in the right age columns with unknown dates`() {
        val scope = listOf("DCIM/Camera/")
        val rows = listOf(
            row("young", taken = now - 10 * 24L * 3600 * 1000),
            row("reminder", taken = now - 95 * 24L * 3600 * 1000),
            row("old", taken = now - 200 * 24L * 3600 * 1000),
            row("noDate", taken = null, added = null),
        )
        val buckets = MediaIndexBuilder.summary(scope, rows, emptyList(), now, zone)
            .folders.single().buckets!!
        assertEquals(BucketStat(1, 10), buckets.under3m!!)
        assertEquals(BucketStat(1, 10), buckets.m3To4!!)
        assertEquals(BucketStat(1, 10), buckets.over4m!!)
        assertEquals(BucketStat(1, 10), buckets.unknown!!)
    }

    @Test
    fun `top level files are carried through and largest is capped at 50`() {
        val scope = listOf("DCIM/Camera/")
        val rows = (1..55).map { row("$it", size = it.toLong()) }
        val topLevel = listOf(TopLevelFile("notes.txt", 123, 5))
        val summary = MediaIndexBuilder.summary(scope, rows, topLevel, now, zone)
        assertEquals(50, summary.largest.size)
        assertEquals(55L, summary.largest.maxOf { it.sizeBytes }) // the biggest are kept
        assertEquals(listOf(TopLevelFile("notes.txt", 123, 5)), summary.topLevelFiles)
    }

    @Test
    fun `stray apks are reported`() {
        val rows = listOf(
            IndexedMedia(ref("apk1", path = "Download/", name = "old-app.apk", size = 999), isImage = false, isVideo = false),
            row("pic"),
        )
        val summary = MediaIndexBuilder.summary(listOf("DCIM/Camera/"), rows, emptyList(), now, zone)
        assertEquals(1, summary.oldApks.size)
        assertEquals("old-app.apk", summary.oldApks.single().name)
        assertEquals(999, summary.oldApks.single().sizeBytes)
    }

    @Test
    fun `candidates hold every scoped item past the cut-off, protected ones included`() {
        val scope = listOf("DCIM/Camera/")
        val cutoff = 120
        val day = 24L * 3600 * 1000
        val rows = listOf(
            row("old1", taken = now - (cutoff + 10) * day),
            row("keep", taken = now - (cutoff + 10) * day, inKeep = true), // included, marked
            row("fav", taken = now - (cutoff + 10) * day, favourite = true), // included, marked
            row("young", taken = now - 10 * day),
            row("elsewhere", path = "Movies/", taken = now - (cutoff + 10) * day),
            row("noAge", taken = null, added = null),
        )
        val candidates = MediaIndexBuilder.candidates(scope, rows, cutoff, now, zone)
        val ids = candidates.items.map { it.mediaId }.toSet()
        assertEquals(setOf("external_primary:images:old1", "external_primary:images:keep", "external_primary:images:fav"), ids)
        assertTrue(candidates.items.single { it.mediaId.endsWith(":keep") }.inKeep)
        assertTrue(candidates.items.single { it.mediaId.endsWith(":fav") }.isFavourite)
    }
}
