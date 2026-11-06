package com.example.phonesteward.update

import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A7: version comparison, and the four refusals before anything is installed. */
class UpdateVerifierTest {

    private lateinit var dir: File

    @Before fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("verifier").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val installedSigner = SignerInfo(current = setOf("aa11"), history = setOf("aa11"), multiple = false)

    private fun apkFile(content: String = "apk-bytes"): Pair<File, ByteArray> {
        val bytes = content.toByteArray()
        return File(dir, "update-5.apk").also { it.writeBytes(bytes) } to bytes
    }

    private fun latestFor(bytes: ByteArray, code: Long = 5) =
        AppLatest(versionName = "0.2.3", versionCode = code, sha256 = sha(bytes), sizeBytes = bytes.size.toLong(), publishedAtMs = 1L)

    private fun verifier(info: ApkInfo?, installedCode: Long = 4) = UpdateVerifier(
        expectedPackage = "com.example.phonesteward",
        installedVersionCode = installedCode,
        installedSigners = installedSigner,
        inspector = { info },
    )

    private val goodInfo = ApkInfo("com.example.phonesteward", 5, installedSigner)

    @Test fun `a newer versionCode is an update, an equal or older one is not`() {
        assertTrue(AppVersion.isNewer(latestCode = 5, installedCode = 4))
        assertFalse(AppVersion.isNewer(latestCode = 4, installedCode = 4))
        assertFalse(AppVersion.isNewer(latestCode = 3, installedCode = 4))
        assertTrue(AppVersion.isNewer(latestCode = 10, installedCode = 9)) // numeric, not text order
    }

    @Test fun `a matching download passes`() {
        val (file, bytes) = apkFile()
        assertEquals(UpdateVerifier.Verdict.Ok, verifier(goodInfo).verify(file, latestFor(bytes)))
    }

    @Test fun `the checksum is compared case-insensitively`() {
        val (file, bytes) = apkFile()
        val upper = latestFor(bytes).copy(sha256 = sha(bytes).uppercase())
        assertEquals(UpdateVerifier.Verdict.Ok, verifier(goodInfo).verify(file, upper))
    }

    @Test fun `refuses on a SHA-256 mismatch`() {
        val (file, bytes) = apkFile()
        val wrong = latestFor(bytes).copy(sha256 = sha("something else".toByteArray()))
        assertEquals(UpdateVerifier.Verdict.Refused(UpdateRefusal.SHA256_MISMATCH), verifier(goodInfo).verify(file, wrong))
    }

    @Test fun `refuses another package name`() {
        val (file, bytes) = apkFile()
        val other = goodInfo.copy(packageName = "com.example.other")
        assertEquals(UpdateVerifier.Verdict.Refused(UpdateRefusal.WRONG_PACKAGE), verifier(other).verify(file, latestFor(bytes)))
    }

    @Test fun `refuses a versionCode that is not the announced one`() {
        val (file, bytes) = apkFile()
        val old = goodInfo.copy(versionCode = 4)
        assertEquals(UpdateVerifier.Verdict.Refused(UpdateRefusal.WRONG_VERSION), verifier(old).verify(file, latestFor(bytes)))
    }

    @Test fun `refuses a download that is not newer than the installed app`() {
        val (file, bytes) = apkFile()
        assertEquals(
            UpdateVerifier.Verdict.Refused(UpdateRefusal.WRONG_VERSION),
            verifier(goodInfo, installedCode = 5).verify(file, latestFor(bytes)),
        )
    }

    @Test fun `refuses a different signing certificate`() {
        val (file, bytes) = apkFile()
        val foreign = goodInfo.copy(signers = SignerInfo(setOf("bb22"), setOf("bb22"), false))
        assertEquals(UpdateVerifier.Verdict.Refused(UpdateRefusal.SIGNER_MISMATCH), verifier(foreign).verify(file, latestFor(bytes)))
    }

    @Test fun `refuses an archive without any signature`() {
        val (file, bytes) = apkFile()
        val unsigned = goodInfo.copy(signers = SignerInfo(emptySet(), emptySet(), false))
        assertEquals(UpdateVerifier.Verdict.Refused(UpdateRefusal.SIGNER_MISMATCH), verifier(unsigned).verify(file, latestFor(bytes)))
    }

    @Test fun `accepts a rotated certificate whose history holds the installed one`() {
        val (file, bytes) = apkFile()
        val rotated = goodInfo.copy(signers = SignerInfo(setOf("cc33"), setOf("aa11", "cc33"), false))
        assertEquals(UpdateVerifier.Verdict.Ok, verifier(rotated).verify(file, latestFor(bytes)))
    }

    @Test fun `multiple signers must match exactly`() {
        val (file, bytes) = apkFile()
        val installed = SignerInfo(setOf("aa11", "dd44"), setOf("aa11", "dd44"), true)
        val v = UpdateVerifier("com.example.phonesteward", 4, installed) { goodInfo.copy(signers = SignerInfo(setOf("aa11"), setOf("aa11"), true)) }
        assertEquals(UpdateVerifier.Verdict.Refused(UpdateRefusal.SIGNER_MISMATCH), v.verify(file, latestFor(bytes)))
    }

    @Test fun `refuses an archive Android cannot read`() {
        val (file, bytes) = apkFile()
        assertEquals(UpdateVerifier.Verdict.Refused(UpdateRefusal.UNREADABLE), verifier(null).verify(file, latestFor(bytes)))
    }

    @Test fun `refuses a missing file and an announced size over the limit`() {
        val (file, bytes) = apkFile()
        file.delete()
        assertEquals(UpdateVerifier.Verdict.Refused(UpdateRefusal.UNREADABLE), verifier(goodInfo).verify(file, latestFor(bytes)))
        val huge = latestFor(bytes).copy(sizeBytes = UpdateController.MAX_APK_BYTES + 1)
        assertEquals(UpdateVerifier.Verdict.Refused(UpdateRefusal.TOO_LARGE), verifier(goodInfo).checkAnnounced(huge))
    }

    @Test fun `the on-disk size must equal the announced size`() {
        val (file, bytes) = apkFile()
        val lying = latestFor(bytes).copy(sizeBytes = bytes.size + 1L)
        assertEquals(UpdateVerifier.Verdict.Refused(UpdateRefusal.SHA256_MISMATCH), verifier(goodInfo).verify(file, lying))
    }

    @Test fun `no reason text carries a path or a file name`() {
        UpdateRefusal.entries.forEach {
            assertFalse(it.code, it.userText.contains("/") || it.userText.contains(".apk"))
            assertTrue(it.code, it.code.matches(Regex("[a-z0-9_]+")))
        }
    }
}
