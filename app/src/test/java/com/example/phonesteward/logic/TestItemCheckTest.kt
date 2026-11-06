package com.example.phonesteward.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding 1 of correction 01: a URI restored from an app-data backup (or a
 * recycled MediaStore id) must never be trusted by the trash path. Before
 * every trash request the row behind the URI is re-read and must match all
 * three markers: our owner package, the test folder, the test name prefix.
 */
class TestItemCheckTest {

    private val app = "com.example.phonesteward"

    @Test
    fun `a valid own test row passes`() {
        assertTrue(
            TestItemCheck.isOwnTestItem(
                ownerPackageName = app,
                relativePath = "Pictures/PhoneStewardTest/",
                displayName = "phone-steward-test-1728215000000.png",
                appPackageName = app,
            ),
        )
    }

    @Test
    fun `a row owned by another package is refused`() {
        assertFalse(
            TestItemCheck.isOwnTestItem(
                ownerPackageName = "com.someoneelse.app",
                relativePath = "Pictures/PhoneStewardTest/",
                displayName = "phone-steward-test-1728215000000.png",
                appPackageName = app,
            ),
        )
    }

    @Test
    fun `a row outside the test folder is refused`() {
        assertFalse(
            TestItemCheck.isOwnTestItem(
                ownerPackageName = app,
                relativePath = "DCIM/Camera/",
                displayName = "phone-steward-test-1728215000000.png",
                appPackageName = app,
            ),
        )
    }

    @Test
    fun `a row whose name lacks the test prefix is refused`() {
        assertFalse(
            TestItemCheck.isOwnTestItem(
                ownerPackageName = app,
                relativePath = "Pictures/PhoneStewardTest/",
                displayName = "IMG_20261006_101010.jpg",
                appPackageName = app,
            ),
        )
    }

    @Test
    fun `a missing row is refused`() {
        assertFalse(
            TestItemCheck.isOwnTestItem(
                ownerPackageName = null,
                relativePath = null,
                displayName = null,
                appPackageName = app,
            ),
        )
    }

    @Test
    fun `a null owner on an otherwise valid row is refused`() {
        assertFalse(
            TestItemCheck.isOwnTestItem(
                ownerPackageName = null,
                relativePath = "Pictures/PhoneStewardTest/",
                displayName = "phone-steward-test-1728215000000.png",
                appPackageName = app,
            ),
        )
    }

    @Test
    fun `the folder must match exactly including the trailing slash`() {
        assertFalse(
            TestItemCheck.isOwnTestItem(
                ownerPackageName = app,
                relativePath = "Pictures/PhoneStewardTest",
                displayName = "phone-steward-test-1728215000000.png",
                appPackageName = app,
            ),
        )
        assertFalse(
            TestItemCheck.isOwnTestItem(
                ownerPackageName = app,
                relativePath = "Pictures/PhoneStewardTest/sub/",
                displayName = "phone-steward-test-1728215000000.png",
                appPackageName = app,
            ),
        )
    }
}
