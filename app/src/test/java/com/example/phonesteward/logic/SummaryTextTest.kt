package com.example.phonesteward.logic

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share summary carries counts and sizes only: no file names and no
 * paths below folder level.
 */
class SummaryTextTest {

    private val london = ZoneId.of("Europe/London")
    private val now = ZonedDateTime.of(2026, 10, 6, 12, 0, 0, 0, london).toInstant().toEpochMilli()

    private fun media(
        name: String,
        path: String,
        bytes: Long,
        ageDays: Long,
        image: Boolean = true,
        favourite: Boolean = false,
    ) = MediaRow(
        displayName = name,
        relativePath = path,
        sizeBytes = bytes,
        dateTakenMillis = now - ageDays * 24L * 60 * 60 * 1000,
        dateAddedMillis = null,
        isImage = image,
        isVideo = !image,
        isFavourite = favourite,
    )

    private fun facts(): Facts {
        val mediaRows = listOf(
            media("IMG_20260901_101010.jpg", "DCIM/Camera", bytes = 3_000_000, ageDays = 10, favourite = true),
            media("IMG_20260902_111111.jpg", "DCIM/Camera", bytes = 2_000_000, ageDays = 20),
            media("VID_20260801_000001.mp4", "DCIM/Camera", bytes = 90_000_000, ageDays = 150, image = false),
            media("wa-img-77.png", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images", bytes = 500_000, ageDays = 5, favourite = true),
        )
        val appRows = listOf(
            AppSizeRow("com.whatsapp", appBytes = 120_000_000, dataBytes = 2_300_000_000, cacheBytes = 640_000_000),
            AppSizeRow("com.android.chrome", appBytes = 90_000_000, dataBytes = 300_000_000, cacheBytes = 1_200_000_000),
        )
        return collect(
            space = object : SpaceSource {
                override fun load() = SpaceRow(freeBytes = 6_338_253_009, totalBytes = 131_100_000_000)
            },
            apps = object : AppSizeSource {
                override fun load() = appRows
            },
            media = object : MediaSource {
                override fun load() = mediaRows
            },
            nowMillis = now,
            zone = london,
        )
    }

    @Test
    fun `summary contains no file names`() {
        val text = SummaryText.build(facts())
        assertFalse(text.contains("IMG_20260901_101010.jpg"))
        assertFalse(text.contains("VID_20260801_000001.mp4"))
        assertFalse(text.contains("wa-img-77.png"))
    }

    @Test
    fun `summary contains no file extension markers`() {
        val text = SummaryText.build(facts())
        assertFalse(text.contains(".jpg"))
        assertFalse(text.contains(".png"))
        assertFalse(text.contains(".mp4"))
    }

    @Test
    fun `summary stops at folder level for media paths`() {
        val text = SummaryText.build(facts())
        assertTrue(text.contains("Android/media/com.whatsapp"))
        assertFalse(text.contains("Android/media/com.whatsapp/WhatsApp"))
    }

    @Test
    fun `summary reports space free and total`() {
        val text = SummaryText.build(facts())
        assertTrue(text.contains("Free 6.3 GB of 131.1 GB"))
    }

    @Test
    fun `summary reports apps with the three way split`() {
        val text = SummaryText.build(facts())
        // com.whatsapp: app 120 MB, data 2.3 GB, cache 640 MB — listed first (largest total).
        val waLine = text.lines().first { it.contains("com.whatsapp") }
        assertTrue(waLine.contains("app 120 MB"))
        assertTrue(waLine.contains("data 2.3 GB"))
        assertTrue(waLine.contains("cache 640 MB"))
        assertTrue(waLine.contains("total 3.1 GB"))
        val chromeLine = text.lines().first { it.contains("com.android.chrome") }
        assertTrue(chromeLine.contains("cache 1.2 GB"))
        // WhatsApp (3.1 GB) sorts above Chrome (1.6 GB).
        assertTrue(text.indexOf("com.whatsapp") < text.indexOf("com.android.chrome"))
    }

    @Test
    fun `summary reports age buckets with counts and bytes`() {
        val text = SummaryText.build(facts())
        val under = text.lines().first { it.startsWith("- Under 3 months") }
        assertTrue(under.contains("3 photos"))
        assertTrue(under.contains("0 videos"))
        assertTrue(under.contains("6 MB"))
        val over = text.lines().first { it.startsWith("- Over 4 months") }
        assertTrue(over.contains("1 videos"))
        assertTrue(over.contains("90 MB"))
    }

    @Test
    fun `summary reports the favourites count`() {
        val text = SummaryText.build(facts())
        assertTrue(text.contains("Favourites: 2"))
    }
}
