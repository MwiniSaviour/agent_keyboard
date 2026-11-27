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
    private val http = FakeHttp(mapOf(entry.files[0].url to bytes))
    private val installer = ModelInstaller(files, http, sleep = {})

    @Test fun freshDownloadVerifiesAndCommitsAtomically() {
        val progress = mutableListOf<Long>()
        val r = installer.download(entry, { false }) { progress += it }
        assertEquals(InstallOutcome.Installed("/models/m1"), r)
        assertTrue(files.finalOf("m1")!!.contentEquals(bytes))
        assertTrue(files.partial.isEmpty())
        assertEquals(bytes.size.toLong(), progress.last())
    }

    @Test fun resumesWithRangeFromThePartialAndRehashesIt() {
        files.openPartial(entry, entry.files[0], append = false).write(bytes, 0, 400_000)
        val r = installer.download(entry, { false }) {}
        assertIs<InstallOutcome.Installed>(r)
        assertEquals(listOf(entry.files[0].url to 400_000L), http.requests)
        assertTrue(files.finalOf("m1")!!.contentEquals(bytes))
    }

    @Test fun serverIgnoringRangeRestartsFromZero() {
        files.openPartial(entry, entry.files[0], append = false).write(bytes, 0, 400_000)
        http.ignoreRange = true
        assertIs<InstallOutcome.Installed>(installer.download(entry, { false }) {})
        assertTrue(files.finalOf("m1")!!.contentEquals(bytes))
    }

    @Test fun corruptPartialFailsVerificationAndIsDeleted() {
        val wrong = bytes.copyOf().also { it[10] = (it[10] + 1).toByte() }
        files.openPartial(entry, entry.files[0], append = false).write(wrong, 0, 400_000)
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
        assertTrue(files.partialLength(entry, entry.files[0]) in 1 until bytes.size)
        assertIs<InstallOutcome.Installed>(installer.download(entry, { false }) {})
    }

    @Test fun oversizedBodiesAndFullDisksFailSafely() {
        val big = FakeHttp(mapOf(entry.files[0].url to bytes + ByteArray(10)))
        val r = ModelInstaller(files, big, sleep = {}).download(entry, { false }) {}
        assertEquals(InstallOutcome.Failed("The download is larger than expected", retryable = false), r)
        assertTrue(files.partial.isEmpty())
        files.free = bytes.size.toLong()
        assertEquals(InstallOutcome.Failed("Not enough free storage", retryable = false), installer.download(entry, { false }) {})
    }

    @Test fun importVerifiesExactlyLikeADownload() {
        assertIs<InstallOutcome.Installed>(installer.import(entry, { ByteArrayInputStream(bytes) }, { false }) {})
        files.final.clear()
        val wrong = bytes.copyOf().also { it[0] = 9 }
        assertIs<InstallOutcome.Failed>(installer.import(entry, { ByteArrayInputStream(wrong) }, { false }) {})
        assertTrue(files.final.isEmpty())
        assertEquals(InstallOutcome.Failed("Downloads are not available in this build", retryable = false), ModelInstaller(files, null).download(entry, { false }) {})
    }

    private val parts = listOf("encoder.onnx" to ByteArray(70_001) { 1 }, "decoder.onnx" to ByteArray(20_000) { 2 }, "tokens.txt" to "a 0\nb 1".toByteArray())
    private val multi = entryFor(ByteArray(0), id = "stt", parts = parts, kind = ModelKind.STT)
    private fun multiHttp() = FakeHttp(multi.files.associate { f -> f.url to parts.first { it.first == f.name }.second })

    @Test fun multiFileModelsDownloadEveryFileIntoOneDirectory() {
        val progress = mutableListOf<Long>()
        val r = ModelInstaller(files, multiHttp(), sleep = {}).download(multi, { false }) { progress += it }
        assertEquals(InstallOutcome.Installed("/models/stt"), r)
        parts.forEach { (name, b) -> assertTrue(files.final["stt/$name"]!!.contentEquals(b), name) }
        assertTrue(files.complete(multi))
        assertEquals(multi.sizeBytes, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a }, "progress is cumulative across files")
    }

    @Test fun multiFileResumeSkipsFinishedFilesAndResumesThePartialOne() {
        val http = multiHttp()
        files.openPartial(multi, multi.files[0], append = false).write(parts[0].second)
        files.commit(multi, multi.files[0])
        files.openPartial(multi, multi.files[1], append = false).write(parts[1].second, 0, 5_000)
        assertEquals(70_001L + 5_000, files.bytesOnDisk(multi))
        assertIs<InstallOutcome.Installed>(ModelInstaller(files, http, sleep = {}).download(multi, { false }) {})
        assertEquals(listOf(multi.files[1].url to 5_000L, multi.files[2].url to 0L), http.requests)
    }

    @Test fun oneBadFileFailsOnlyThatFile() {
        val http = FakeHttp(multi.files.associate { f -> f.url to if (f.name == "tokens.txt") "a 0\nb 2".toByteArray() else parts.first { it.first == f.name }.second })
        val r = ModelInstaller(files, http, sleep = {}).download(multi, { false }) {}
        assertEquals(InstallOutcome.Failed("Verification failed (SHA-256 mismatch)", retryable = true), r)
        assertTrue(files.hasFinal(multi, multi.files[0]) && files.hasFinal(multi, multi.files[1]))
        assertFalse(files.hasFinal(multi, multi.files[2]))
    }

    @Test fun partialImportsReportWhatIsMissing() {
        val r = installer.import(multi, { f -> if (f.name == "tokens.txt") null else ByteArrayInputStream(parts.first { it.first == f.name }.second) }, { false }) {}
        assertEquals(InstallOutcome.Incomplete(listOf("tokens.txt")), r)
        assertIs<InstallOutcome.Installed>(installer.import(multi, { ByteArrayInputStream(parts[2].second) }, { false }) {})
    }

    @Test fun unverifiedImportsMeasureWhatTheyCopied() {
        val custom = entryFor(bytes, id = "custom-x")
        val (r, measured) = installer.importUnverified(custom, ByteArrayInputStream(bytes), { false }) {}
        assertEquals(InstallOutcome.Installed("/models/custom-x"), r)
        assertEquals(Measured(sha256Hex(bytes), bytes.size.toLong()), measured)
        files.final.clear()
        val (short, none) = installer.importUnverified(custom, ByteArrayInputStream(bytes.copyOf(10)), { false }) {}
        assertEquals(InstallOutcome.Failed("The file changed while it was copied", retryable = true), short)
        assertEquals(null, none)
        assertTrue(files.final.isEmpty() && files.partial.isEmpty())
    }

    @Test fun multiFileShaIsTheHashOfTheFileList() {
        val expected = sha256Hex(multi.files.joinToString("\n") { "${it.name}:${it.sha256}" }.toByteArray())
        assertEquals(expected, multi.sha256)
        assertEquals(entry.files[0].sha256, entry.sha256)
    }
}
