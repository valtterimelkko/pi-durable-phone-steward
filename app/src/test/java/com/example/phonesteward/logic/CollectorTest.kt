package com.example.phonesteward.logic

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The collector glues the three sources into one [Facts] value with the pure
 * helpers; these tests use fakes so the aggregation itself is covered on the
 * JVM without Android.
 */
class CollectorTest {

    private val london = ZoneId.of("Europe/London")
    private val now = ZonedDateTime.of(2026, 10, 6, 12, 0, 0, 0, london).toInstant().toEpochMilli()

    private fun media(
        name: String,
        path: String?,
        bytes: Long,
        ageDays: Long? = null,
        image: Boolean = true,
        video: Boolean = false,
        favourite: Boolean = false,
    ) = MediaRow(
        displayName = name,
        relativePath = path,
        sizeBytes = bytes,
        dateTakenMillis = ageDays?.let { now - it * 24L * 60 * 60 * 1000 },
        dateAddedMillis = null,
        isImage = image,
        isVideo = video,
        isFavourite = favourite,
    )

    private class FakeSpace(private val row: SpaceRow) : SpaceSource {
        override fun load() = row
    }

    private class FakeApps(private val rows: List<AppSizeRow>) : AppSizeSource {
        override fun load() = rows
    }

    private class FakeMedia(private val rows: List<MediaRow>) : MediaSource {
        override fun load() = rows
    }

    @Test
    fun `collects space through the source`() {
        val facts = collect(
            space = FakeSpace(SpaceRow(freeBytes = 6_000_000_000, totalBytes = 130_000_000_000)),
            apps = FakeApps(emptyList()),
            media = FakeMedia(emptyList()),
            nowMillis = now,
            zone = london,
        )
        assertEquals(6_000_000_000L, facts.space.freeBytes)
        assertEquals(130_000_000_000L, facts.space.totalBytes)
    }

    @Test
    fun `top apps are sorted by total size`() {
        val facts = collect(
            space = FakeSpace(SpaceRow(0, 0)),
            apps = FakeApps(
                listOf(
                    AppSizeRow("small.app", 1_000, 0, 0),
                    AppSizeRow("big.app", 5_000, 0, 0),
                    AppSizeRow("middle.app", 3_000, 0, 0),
                ),
            ),
            media = FakeMedia(emptyList()),
            nowMillis = now,
            zone = london,
        )
        assertEquals(
            listOf("big.app", "middle.app", "small.app"),
            facts.topApps.map { it.packageName },
        )
    }

    @Test
    fun `top apps with equal totals keep source order`() {
        val facts = collect(
            space = FakeSpace(SpaceRow(0, 0)),
            apps = FakeApps(
                listOf(
                    AppSizeRow("first", 100, 0, 0),
                    AppSizeRow("second", 100, 0, 0),
                ),
            ),
            media = FakeMedia(emptyList()),
            nowMillis = now,
            zone = london,
        )
        assertEquals(listOf("first", "second"), facts.topApps.map { it.packageName })
    }

    @Test
    fun `folders group whatsapp media and skip nothing else`() {
        val facts = collect(
            space = FakeSpace(SpaceRow(0, 0)),
            apps = FakeApps(emptyList()),
            media = FakeMedia(
                listOf(
                    media("a.mp4", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video", 400, ageDays = 1, video = true, image = false),
                    media("b.jpg", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images", 200, ageDays = 2),
                    media("c.pdf", "Download", 10, ageDays = null, image = false),
                ),
            ),
            nowMillis = now,
            zone = london,
        )
        assertEquals(
            listOf("Android/media/com.whatsapp", "Download"),
            facts.topFolders.map { it.folderKey },
        )
        assertEquals(600L, facts.topFolders[0].bytes)
        assertEquals(2, facts.topFolders[0].itemCount)
    }

    @Test
    fun `buckets count photos videos and bytes and cover all four buckets`() {
        val facts = collect(
            space = FakeSpace(SpaceRow(0, 0)),
            apps = FakeApps(emptyList()),
            media = FakeMedia(
                listOf(
                    media("young.jpg", "DCIM/Camera", 100, ageDays = 5),
                    media("young2.jpg", "DCIM/Camera", 50, ageDays = 2),
                    media("mid.mp4", "DCIM/Camera", 1_000, ageDays = 110, image = false, video = true),
                    media("old.jpg", "Pictures/Screenshots", 10_000, ageDays = 200),
                    media("doc.pdf", "Download", 7, ageDays = 5, image = false), // not media: no bucket
                ),
            ),
            nowMillis = now,
            zone = london,
        )
        assertEquals(AgeBucket.entries.toSet(), facts.buckets.keys)
        assertEquals(BucketRow(photos = 2, videos = 0, bytes = 150), facts.buckets[AgeBucket.UNDER_3_MONTHS])
        assertEquals(BucketRow(photos = 0, videos = 1, bytes = 1_000), facts.buckets[AgeBucket.MONTHS_3_TO_4])
        assertEquals(BucketRow(photos = 1, videos = 0, bytes = 10_000), facts.buckets[AgeBucket.OVER_4_MONTHS])
        assertEquals(BucketRow(photos = 0, videos = 0, bytes = 0), facts.buckets[AgeBucket.UNKNOWN])
    }

    @Test
    fun `items with no date land in the unknown bucket`() {
        val facts = collect(
            space = FakeSpace(SpaceRow(0, 0)),
            apps = FakeApps(emptyList()),
            media = FakeMedia(
                listOf(
                    media("mystery.jpg", "DCIM/Camera", 42, ageDays = null),
                ),
            ),
            nowMillis = now,
            zone = london,
        )
        assertEquals(BucketRow(photos = 1, videos = 0, bytes = 42), facts.buckets[AgeBucket.UNKNOWN])
    }

    @Test
    fun `favourites count only favourite media`() {
        val facts = collect(
            space = FakeSpace(SpaceRow(0, 0)),
            apps = FakeApps(emptyList()),
            media = FakeMedia(
                listOf(
                    media("fav.jpg", "DCIM/Camera", 1, ageDays = 1, favourite = true),
                    media("plain.jpg", "DCIM/Camera", 1, ageDays = 2),
                    media("favvid.mp4", "DCIM/Camera", 1, ageDays = 3, image = false, video = true, favourite = true),
                    media("favdoc.pdf", "Download", 1, ageDays = 4, image = false, favourite = true),
                ),
            ),
            nowMillis = now,
            zone = london,
        )
        assertEquals(2, facts.favouriteCount)
    }
}
