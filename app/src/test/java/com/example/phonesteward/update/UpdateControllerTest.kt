package com.example.phonesteward.update

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.example.phonesteward.net.ApiException
import com.example.phonesteward.net.TransportException

/** A7: the update flow end to end on fakes — check, permission, download, verify, install. */
class UpdateControllerTest {

    private lateinit var dir: File
    private val events = mutableListOf<String>()
    private val errors = mutableListOf<String>()

    @Before fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("controller").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val apkBytes = "the new apk".toByteArray()
    private val latest = AppLatest("0.2.3", 5, sha(apkBytes), apkBytes.size.toLong(), 1L)
    private val signer = SignerInfo(setOf("aa11"), setOf("aa11"), false)

    private class FakeServer(
        var latest: AppLatest?,
        var bytes: ByteArray,
        var latestError: Exception? = null,
        var downloadError: Exception? = null,
    ) : UpdateServer {
        var downloads = 0
        override fun latest(): AppLatest? {
            latestError?.let { throw it }
            return latest
        }
        override fun download(latest: AppLatest, dest: File, maxBytes: Long, onProgress: (Long) -> Unit) {
            downloads += 1
            downloadError?.let { throw it }
            dest.writeBytes(bytes)
            onProgress(bytes.size.toLong())
        }
    }

    private class FakeInstaller(var fail: Exception? = null) : UpdateInstaller {
        val installed = mutableListOf<Pair<String, Long>>()
        override fun install(file: File, versionCode: Long) {
            fail?.let { throw it }
            installed += file.name to versionCode
        }
    }

    private class FakePermission(var allowed: Boolean) : InstallPermission {
        var opened = 0
        override fun canInstall() = allowed
        override fun openSettings() { opened += 1 }
    }

    private fun controller(
        server: FakeServer,
        installer: FakeInstaller = FakeInstaller(),
        permission: FakePermission = FakePermission(true),
        info: ApkInfo? = ApkInfo("com.example.phonesteward", 5, signer),
        installedCode: Long = 4,
    ) = UpdateController(
        installedVersionCode = installedCode,
        installedVersionName = "0.2.2",
        server = server,
        verifier = UpdateVerifier("com.example.phonesteward", installedCode, signer) { info },
        installer = installer,
        permission = permission,
        workDir = File(dir, "updates"),
        log = object : UpdateLog {
            override fun event(level: String, event: String, detail: String) { events += "$event $detail".trim() }
            override fun error(site: String, error: Throwable) { errors += "$site ${error.message}" }
        },
    )

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    @Test fun `no release means nothing to update`() = run {
        val c = controller(FakeServer(null, apkBytes))
        c.check()
        assertEquals(UpdateUiState.UpToDate("0.2.2"), c.state.value)
        assertTrue(events.single().startsWith("update_check"))
    }

    @Test fun `the same or an older release is up to date`() = run {
        val c = controller(FakeServer(latest.copy(versionCode = 4), apkBytes))
        c.check()
        assertEquals(UpdateUiState.UpToDate("0.2.2"), c.state.value)
    }

    @Test fun `a newer release is offered`() = run {
        val c = controller(FakeServer(latest, apkBytes))
        c.check()
        assertEquals(UpdateUiState.Available("0.2.2", latest), c.state.value)
        assertTrue(events.any { it.startsWith("update_check") && it.contains("latest=5") && it.contains("installed=4") })
    }

    @Test fun `an unreachable server is a quiet failure, not an error report`() = run {
        val c = controller(FakeServer(null, apkBytes, latestError = TransportException(IOException("offline"))))
        c.check()
        assertTrue(c.state.value is UpdateUiState.CheckFailed)
        assertTrue(errors.isEmpty())
    }

    @Test fun `a server error on the check is recorded`() = run {
        val c = controller(FakeServer(null, apkBytes, latestError = ApiException(500, "RELEASE_CORRUPT")))
        c.check()
        assertTrue(c.state.value is UpdateUiState.CheckFailed)
        assertEquals(1, errors.size)
        assertTrue(errors[0].startsWith("update.check"))
    }

    @Test fun `one tap downloads, verifies and installs in place`() = run {
        val server = FakeServer(latest, apkBytes)
        val installer = FakeInstaller()
        val c = controller(server, installer)
        c.check()
        c.update()
        assertEquals(listOf("update-5.apk" to 5L), installer.installed)
        assertEquals(UpdateUiState.Installing("0.2.2", latest), c.state.value)
        val names = events.map { it.substringBefore(' ') }
        assertEquals(listOf("update_check", "update_download", "update_download", "update_verified", "update_install"), names)
        assertTrue(errors.isEmpty())
    }

    @Test fun `Android's answers drive the final state`() = run {
        val c = controller(FakeServer(latest, apkBytes))
        c.check(); c.update()
        c.onInstallStatus(InstallStatus.PENDING_USER_ACTION)
        assertEquals(UpdateUiState.Installing("0.2.2", latest), c.state.value)
        assertTrue(events.last() == "update_install STATUS_PENDING_USER_ACTION")
        c.onInstallStatus(InstallStatus.FAILURE_ABORTED)
        assertTrue(c.state.value is UpdateUiState.Failed)
        assertTrue(errors.isEmpty()) // the owner pressing Cancel is not an error
        assertEquals("update_install STATUS_FAILURE_ABORTED", events.last())
    }

    @Test fun `a real install failure is reported with its status name`() = run {
        val c = controller(FakeServer(latest, apkBytes))
        c.check(); c.update()
        c.onInstallStatus(InstallStatus.FAILURE_CONFLICT)
        assertTrue(c.state.value is UpdateUiState.Failed)
        assertEquals(1, errors.size)
        assertTrue(errors[0].startsWith("update.install"))
        assertTrue(errors[0].contains("STATUS_FAILURE_CONFLICT"))
    }

    @Test fun `a refused download is never installed, is deleted, logged and reported`() = run {
        val installer = FakeInstaller()
        val c = controller(FakeServer(latest, "tampered".toByteArray()), installer)
        c.check(); c.update()
        assertTrue(installer.installed.isEmpty())
        val state = c.state.value as UpdateUiState.Refused
        assertEquals(UpdateRefusal.SHA256_MISMATCH, state.reason)
        assertTrue(events.contains("update_refused sha256_mismatch"))
        assertEquals(1, errors.size)
        assertTrue(errors[0].startsWith("update.verify"))
        assertTrue(errors[0].contains("sha256_mismatch"))
        assertFalse(File(dir, "updates").listFiles().orEmpty().any { it.name.endsWith(".apk") })
    }

    @Test fun `a different signer is refused`() = run {
        val installer = FakeInstaller()
        val foreign = ApkInfo("com.example.phonesteward", 5, SignerInfo(setOf("bb22"), setOf("bb22"), false))
        val c = controller(FakeServer(latest, apkBytes), installer, info = foreign)
        c.check(); c.update()
        assertEquals(UpdateRefusal.SIGNER_MISMATCH, (c.state.value as UpdateUiState.Refused).reason)
        assertTrue(installer.installed.isEmpty())
    }

    @Test fun `Install unknown apps first - the flow stops before any download and resumes after the switch`() = run {
        val server = FakeServer(latest, apkBytes)
        val installer = FakeInstaller()
        val permission = FakePermission(false)
        val c = controller(server, installer, permission)
        c.check(); c.update()
        assertEquals(UpdateUiState.NeedsPermission("0.2.2", latest), c.state.value)
        assertEquals(1, permission.opened)
        assertEquals(0, server.downloads)

        // The owner returns without flipping the switch: nothing happens.
        c.resumeIfPermitted()
        assertEquals(0, server.downloads)
        assertTrue(c.state.value is UpdateUiState.NeedsPermission)

        // The owner flips it and returns: the update carries on by itself .
        permission.allowed = true
        c.resumeIfPermitted()
        assertEquals(1, server.downloads)
        assertEquals(1, installer.installed.size)
    }

    @Test fun `resuming does nothing when no update was waiting for the switch`() = run {
        val server = FakeServer(latest, apkBytes)
        val c = controller(server)
        c.check()
        c.resumeIfPermitted()
        assertEquals(0, server.downloads)
    }

    @Test fun `a release that changed under us goes back to checking`() = run {
        val server = FakeServer(latest, apkBytes, downloadError = ApiException(409, "RELEASE_CHANGED"))
        val c = controller(server)
        c.check()
        server.latest = latest.copy(versionName = "0.2.4", versionCode = 6)
        c.update()
        assertEquals("0.2.4", (c.state.value as UpdateUiState.Available).latest.versionName)
        assertTrue(errors.isEmpty())
    }

    @Test fun `an interrupted download fails quietly and can be retried`() = run {
        val server = FakeServer(latest, apkBytes, downloadError = TransportException(IOException("reset")))
        val installer = FakeInstaller()
        val c = controller(server, installer)
        c.check(); c.update()
        assertTrue(c.state.value is UpdateUiState.Failed)
        assertTrue(errors.isEmpty())
        server.downloadError = null
        c.update()
        assertEquals(1, installer.installed.size)
    }

    @Test fun `a download that is too large is refused`() = run {
        val server = FakeServer(latest, apkBytes, downloadError = ApkTooLargeException(UpdateController.MAX_APK_BYTES + 1))
        val c = controller(server)
        c.check(); c.update()
        assertEquals(UpdateRefusal.TOO_LARGE, (c.state.value as UpdateUiState.Refused).reason)
    }

    @Test fun `an announced size over the limit is refused before downloading`() = run {
        val server = FakeServer(latest.copy(sizeBytes = UpdateController.MAX_APK_BYTES + 1), apkBytes)
        val c = controller(server)
        c.check(); c.update()
        assertEquals(UpdateRefusal.TOO_LARGE, (c.state.value as UpdateUiState.Refused).reason)
        assertEquals(0, server.downloads)
    }

    @Test fun `a failing session commit is reported and recoverable`() = run {
        val installer = FakeInstaller(fail = IllegalStateException("session failed"))
        val c = controller(FakeServer(latest, apkBytes), installer)
        c.check(); c.update()
        assertTrue(c.state.value is UpdateUiState.Failed)
        assertTrue(errors[0].startsWith("update.install"))
    }

    @Test fun `update is ignored while an update is already running`() = run {
        val server = FakeServer(latest, apkBytes)
        val installer = FakeInstaller()
        val c = controller(server, installer)
        c.check(); c.update()
        c.update() // second tap while Installing
        assertEquals(1, server.downloads)
        assertEquals(1, installer.installed.size)
    }

    @Test fun `update without a known release does nothing`() = run {
        val server = FakeServer(latest, apkBytes)
        val c = controller(server)
        c.update()
        assertEquals(0, server.downloads)
        assertNotNull(c.state.value)
    }

    @Test fun `after a restart a recorded target that is now installed logs success once`() = run {
        val c = controller(FakeServer(latest, apkBytes), installedCode = 5)
        c.noteInstallOutcomeAtStart(pendingTargetVersionCode = 5)
        assertEquals(listOf("update_install success"), events)
        events.clear()
        c.noteInstallOutcomeAtStart(pendingTargetVersionCode = 0)
        assertTrue(events.isEmpty())
    }

    @Test fun `after Android restarted the app at the switch the update carries on by itself`() = run {
        val installer = FakeInstaller()
        val permission = FakePermission(true)
        val c = controller(FakeServer(latest, apkBytes), installer, permission)
        c.continueAfterPermissionRestart()
        assertEquals(listOf("update-5.apk" to 5L), installer.installed)
        assertEquals("no second trip to Settings", 0, permission.opened)
    }

    @Test fun `after the restart without the switch the owner only sees the Update button`() = run {
        val installer = FakeInstaller()
        val permission = FakePermission(false)
        val c = controller(FakeServer(latest, apkBytes), installer, permission)
        c.continueAfterPermissionRestart()
        assertEquals(UpdateUiState.Available("0.2.2", latest), c.state.value)
        assertTrue(installer.installed.isEmpty())
        assertEquals(0, permission.opened)
    }
}
