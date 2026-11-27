package com.agentkeyboard.core.models

import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelInstallerTest {
    private val bytes = ByteArray(1_000_003) { (it * 31 % 251).toByte() }
    private val entry = entryFor(bytes)
    private val files = MemFiles()
    private val http = FakeHttp(mapOf(entry.url to bytes))
    private val installer = ModelInstaller(files, http, sleep = {})

    @Test fun freshDownloadVerifiesAndCommitsAtomically() {
        val progress = mutableListOf<Long>()
        val r = installer.download(entry, { false }) { progress += it }
        assertEquals(InstallOutcome.Installed("/models/m1.litertlm"), r)
        assertTrue(files.final["m1"]!!.contentEquals(bytes))
        assertTrue(files.partial.isEmpty())
        assertEquals(bytes.size.toLong(), progress.last())
    }

    @Test fun resumesWithRangeFromThePartialAndRehashesIt() {
        files.openPartial(entry, append = false).write(bytes, 0, 400_000)
        val r = installer.download(entry, { false }) {}
        assertIs<InstallOutcome.Installed>(r)
        assertEquals(listOf(entry.url to 400_000L), http.requests)
        assertTrue(files.final["m1"]!!.contentEquals(bytes))
    }

    @Test fun serverIgnoringRangeRestartsFromZero() {
        files.openPartial(entry, append = false).write(bytes, 0, 400_000)
        http.ignoreRange = true
        assertIs<InstallOutcome.Installed>(installer.download(entry, { false }) {})
        assertTrue(files.final["m1"]!!.contentEquals(bytes))
    }

    @Test fun corruptPartialFailsVerificationAndIsDeleted() {
        val wrong = bytes.copyOf().also { it[10] = (it[10] + 1).toByte() }
        files.openPartial(entry, append = false).write(wrong, 0, 400_000)
        val r = installer.download(entry, { false }) {}
        assertEquals(InstallOutcome.Failed("Verification failed (SHA-256 mismatch)", retryable = true), r)
        assertTrue(files.partial.isEmpty() && files.final.isEmpty())
    }

    @Test fun droppedConnectionsAreRetriedAndResumed() {
        http.failures = 2
        http.cutAfter = 300_000
        // first two attempts throw, third delivers 300k then closes, fourth resumes from 300k
        assertIs<InstallOutcome.Installed>(installer.download(entry, { false }) {})
        assertEquals(listOf(0L, 0L, 0L, 300_000L), http.requests.map { it.second })
    }

    @Test fun permanentErrorsAreNotRetried() {
        http.status = 404
        assertEquals(InstallOutcome.Failed("Download refused (404)", retryable = false), installer.download(entry, { false }) {})
        assertEquals(1, http.requests.size)
        http.status = 503
        val r = installer.download(entry, { false }) {}
        assertIs<InstallOutcome.Failed>(r)
        assertTrue(r.retryable)
        assertEquals(1 + 1 + ModelInstaller.MAX_ATTEMPTS, http.requests.size)
    }

    @Test fun stopKeepsThePartialForResume() {
        var calls = 0
        val r = installer.download(entry, { calls++ > 2 }) {}
        assertIs<InstallOutcome.Paused>(r)
        assertTrue(files.partialLength(entry) in 1 until bytes.size)
        assertIs<InstallOutcome.Installed>(installer.download(entry, { false }) {})
    }

    @Test fun oversizedBodiesAndFullDisksFailSafely() {
        val big = FakeHttp(mapOf(entry.url to bytes + ByteArray(10)))
        val r = ModelInstaller(files, big, sleep = {}).download(entry, { false }) {}
        assertEquals(InstallOutcome.Failed("The download is larger than expected", retryable = false), r)
        assertTrue(files.partial.isEmpty())
        files.free = bytes.size.toLong()
        assertEquals(InstallOutcome.Failed("Not enough free storage", retryable = false), installer.download(entry, { false }) {})
    }

    @Test fun importVerifiesExactlyLikeADownload() {
        assertIs<InstallOutcome.Installed>(installer.import(entry, ByteArrayInputStream(bytes), { false }) {})
        files.final.clear()
        val wrong = bytes.copyOf().also { it[0] = 9 }
        assertIs<InstallOutcome.Failed>(installer.import(entry, ByteArrayInputStream(wrong), { false }) {})
        assertFalse("m1" in files.final)
        assertEquals(InstallOutcome.Failed("Downloads are not available in this build", retryable = false), ModelInstaller(files, null).download(entry, { false }) {})
    }
}
