package com.example.phonesteward.android

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 0.2.4 correction 1: ACTION_DELETE never returns a result; ACTION_UNINSTALL_PACKAGE with
 * EXTRA_RETURN_RESULT answers RESULT_OK only after the removal, RESULT_CANCELED on Cancel and
 * RESULT_FIRST_USER on failure (deprecated since API 29, still honoured on 33 and 35).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class UninstallIntentTest {

    @Suppress("DEPRECATION")
    @Test
    fun `the uninstall intent asks Android for a result`() {
        val intent = Permissions.uninstallIntent("com.invented.app")
        assertEquals(Intent.ACTION_UNINSTALL_PACKAGE, intent.action)
        assertEquals("package:com.invented.app", intent.dataString)
        assertTrue("EXTRA_RETURN_RESULT must be true", intent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false))
    }
}
