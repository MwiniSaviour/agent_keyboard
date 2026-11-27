package com.agentkeyboard.core.models

import com.agentkeyboard.core.protocol.DeviceTier
import java.io.InputStream

/*
 * Models already on the phone (ADR-0012). Users often have model files from other apps or from a
 * computer: re-downloading gigabytes they already own is wasteful. The host lists candidate files in
 * places this app may read; [ModelMatcher] decides what each one is.
 *
 *  - matches a catalog entry (name + exact size, every file in one folder): installed by copying it
 *    through the normal verifier, so it is used only if its SHA-256 matches the signed catalog;
 *  - any other `.litertlm`: offered as an *unverified* model the user may choose to trust.
 *
 * Nothing found is ever loaded in place: it is copied into app-private storage first, so the bytes
 * can't change under the runtime, and the user's file is left exactly as it was.
 */

/** A file the host can read. [ref] is opaque to core (a content URI or an absolute path). */
data class FoundFile(
    val ref: String,
    val name: String,
    val sizeBytes: Long,
    /** Groups files that sit in the same folder (multi-file models must be complete in one place). */
    val folder: String,
    /** Where it is, for people: "Download", "Documents/models". */
    val where: String,
)

data class ScanResult(
    val files: List<FoundFile>,
    /** `app` (only this app's own folders), `folders` (+ folders the user granted) or `all` (all-files access). */
    val access: String,
    /** Folders the user has granted, for the Models screen. */
    val folders: Int,
    /** This build may ask for all-files access (`full` flavor). */
    val allFilesOption: Boolean = false,
)

interface ModelScanner {
    /** Blocking; runs on the I/O thread. Bounded by the host (depth, entries, time). */
    fun scan(): ScanResult
    fun open(ref: String): InputStream

    /**
     * [refs] were copied into app storage. The host may drop access it holds only for them (files the
     * user picked one by one); granted folders are kept. Runs on the I/O thread.
     */
    fun copied(refs: Collection<String>) {}
}

/** A model the scan recognised. */
data class FoundModel(
    val id: String,
    val name: String,
    val kind: ModelKind,
    val sizeBytes: Long,
    val where: String,
    /** The catalog entry it claims to be (verified on copy), or null for an unverified custom model. */
    val entryId: String?,
    /** File name in the entry → [FoundFile.ref]. */
    val refs: Map<String, String>,
)

object ModelMatcher {
    /** Smaller `.litertlm` files are tokenizer/test artefacts, not models. */
    const val MIN_CUSTOM_BYTES = 50L * 1024 * 1024
    const val CUSTOM_PREFIX = "custom-"
    /**
     * The on-disk name of every custom model's copy. The original name comes from another app's
     * storage (a SAF provider controls it), so it is shown to the user but never used as a path.
     */
    const val CUSTOM_FILE = "model.litertlm"

    /**
     * [installed] maps model id → the fingerprint installed now, so a file identical to what is already
     * installed isn't offered again.
     */
    fun match(entries: List<ModelEntry>, files: List<FoundFile>, installed: Map<String, String>): List<FoundModel> {
        val out = ArrayList<FoundModel>()
        val claimed = HashSet<String>()
        for (e in entries) {
            if (e.custom || installed[e.id] == e.sha256) continue
            val hit = files.groupBy { it.folder }.values.firstNotNullOfOrNull { inFolder -> refsFor(e, inFolder) }
                ?: if (e.files.size == 1) renamed(e, files) else null
            if (hit != null) {
                claimed += hit.values
                out += FoundModel("found-${e.id}", e.name, e.kind, e.sizeBytes, files.first { it.ref == hit.values.first() }.where, e.id, hit)
            }
        }
        for (f in files) {
            if (f.ref in claimed || !f.name.endsWith(".litertlm", ignoreCase = true) || f.sizeBytes < MIN_CUSTOM_BYTES) continue
            val id = customId(f.name, f.sizeBytes)
            if (id in installed || out.any { it.id == id }) continue
            out += FoundModel(id, prettyName(f.name), ModelKind.LLM, f.sizeBytes, f.where, null, mapOf(f.name to f.ref))
        }
        return out
    }

    /** Every file of [e] present in one folder with its exact size. */
    private fun refsFor(e: ModelEntry, inFolder: List<FoundFile>): Map<String, String>? {
        val refs = HashMap<String, String>()
        for (mf in e.files) {
            val f = inFolder.firstOrNull { it.name.equals(mf.name, ignoreCase = true) && it.sizeBytes == mf.sizeBytes } ?: return null
            refs[mf.name] = f.ref
        }
        return refs
    }

    /** A renamed single-file model: same extension and the only file of exactly that size. */
    private fun renamed(e: ModelEntry, files: List<FoundFile>): Map<String, String>? {
        val ext = e.file.substringAfterLast('.', "").lowercase()
        val same = files.filter { it.sizeBytes == e.sizeBytes && it.name.substringAfterLast('.', "").lowercase() == ext }
        return same.singleOrNull()?.let { mapOf(e.file to it.ref) }
    }

    /** Stable id for a custom model, so rescans of the same file map to the same entry. */
    fun customId(name: String, size: Long): String {
        val stem = name.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(36).ifEmpty { "model" }
        return "$CUSTOM_PREFIX${size.toString(36)}-$stem"
    }

    fun prettyName(name: String): String =
        name.substringBeforeLast('.').replace('_', ' ').replace(Regex("\\s+"), " ").trim().take(60).ifEmpty { "Model" }

    /**
     * The entry for an unverified model the user chose to trust. Conservative defaults: text only,
     * a 1024-token context (fits any export), the GPU only after ModelManager's crash-safe probe.
     */
    fun customEntry(found: FoundModel, measured: Measured?): ModelEntry {
        val size = measured?.sizeBytes ?: found.sizeBytes
        return ModelEntry(
            id = found.id,
            kind = ModelKind.LLM,
            name = found.name,
            role = "Your model, found on this phone (unverified)",
            files = listOf(ModelFile(CUSTOM_FILE, "file://local", measured?.sha256 ?: "0".repeat(64), size)),
            license = "Unknown: check where it came from",
            licenseUrl = null,
            tiers = DeviceTier.entries.toSet(),
            coreFor = emptySet(),
            capabilities = setOf(Capability.TEXT),
            backends = listOf(Backend.GPU, Backend.CPU),
            runtime = "litertlm",
            contextTokens = 1_024,
            // Weights are memory-mapped; the working set is roughly the file plus KV cache and buffers.
            ramMb = ((size / (1024 * 1024)) * 1.1 + 300).toInt().coerceIn(1, 16_384),
            minAppVersion = 1,
            quality = 50,
            custom = true,
        )
    }
}
