package com.agentkeyboard.app.models

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import com.agentkeyboard.app.AkLog
import com.agentkeyboard.core.json.Json
import com.agentkeyboard.core.json.JsonException
import com.agentkeyboard.core.json.obj
import com.agentkeyboard.core.json.objOrNull
import com.agentkeyboard.core.json.str
import com.agentkeyboard.core.models.DeviceSpecs
import com.agentkeyboard.core.models.FoundFile
import com.agentkeyboard.core.models.InstalledModel
import com.agentkeyboard.core.models.ModelScanner
import com.agentkeyboard.core.models.ModelService
import com.agentkeyboard.core.models.ScanResult
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/*
 * Device facts, the voice-model pointer and the on-device model scan (ADR-0012).
 */

object AndroidSpecs {
    /** OpenCL drivers LiteRT-LM's GPU backend loads; their presence is a cheap "GPU worth trying" signal. */
    private val GPU_LIBS = listOf(
        "/vendor/lib64/libOpenCL.so", "/system/vendor/lib64/libOpenCL.so", "/system/lib64/libOpenCL.so",
        "/vendor/lib64/egl/libGLES_mali.so",
    )

    fun read(context: Context): DeviceSpecs {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null
        return DeviceSpecs(
            totalRamMb = (mem.totalMem / (1024 * 1024)).toInt(),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            gpu = GPU_LIBS.any { File(it).exists() },
            lowRam = am.isLowRamDevice,
            soc = soc,
        )
    }
}

/**
 * Where the keyboard process finds the voice model. `:agent` owns the install records (in its
 * encrypted database); the keyboard must not bind `:agent` just to start, so `:agent` publishes the
 * active speech model's files here whenever its choice changes. App-private, same UID.
 */
data class SttPointer(
    val id: String,
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
    val vad: String?,
    val modelType: String,
) {
    companion object {
        private fun file(context: Context) = File(AndroidModelFiles.root(context), AndroidModelFiles.STT_POINTER)

        /** Publishes [model] (or clears the pointer for null). Call off the main thread. */
        fun publish(context: Context, model: InstalledModel?) {
            val target = file(context)
            if (model == null) {
                target.delete()
                return
            }
            val c = model.entry.config
            fun path(role: String) = c[role]?.let(model::file)
            val json = obj(
                "id" to model.entry.id, "encoder" to path("encoder"), "decoder" to path("decoder"), "joiner" to path("joiner"),
                "tokens" to path("tokens"), "vad" to path("vad"), "modelType" to (c["modelType"] ?: ""),
            )
            AndroidModelFiles.writeAtomically(target, Json.write(json))
        }

        fun read(context: Context): SttPointer? {
            val f = file(context)
            if (!f.isFile) return null
            val o = try {
                Json.parse(f.readText()).objOrNull()
            } catch (e: JsonException) {
                null
            } catch (e: IOException) {
                null
            } ?: return null
            fun req(k: String) = o[k].str()?.takeIf { File(it).isFile }
            return SttPointer(
                id = o["id"].str() ?: return null,
                encoder = req("encoder") ?: return null,
                decoder = req("decoder") ?: return null,
                joiner = req("joiner") ?: return null,
                tokens = req("tokens") ?: return null,
                vad = req("vad"),
                modelType = o["modelType"].str().orEmpty(),
            )
        }
    }
}

/**
 * Lists candidate model files in places this app may read, bounded in depth, entries and time:
 *  - its own external folder (`Android/data/<pkg>/files/models`), where files can be copied over USB;
 *  - folders the user granted from the Models screen (Storage Access Framework, persisted);
 *  - with all-files access (`full` builds only, the user's choice): Download, Documents and the
 *    other top-level folders of shared storage.
 * Only names that can be part of a model are reported; nothing is opened until the user adopts one.
 */
class AndroidModelScanner(private val context: Context, private val allFilesOption: Boolean) : ModelScanner {

    private class Walk(val deadline: Long) {
        val out = ArrayList<FoundFile>()
        var visited = 0
        val exhausted get() = visited >= MAX_ENTRIES || SystemClock.elapsedRealtime() > deadline || out.size >= MAX_RESULTS
    }

    override fun scan(): ScanResult {
        val walk = Walk(SystemClock.elapsedRealtime() + BUDGET_MS)
        context.getExternalFilesDir(null)?.let { appDir ->
            val models = File(appDir, "models").apply { mkdirs() }
            walkFiles(models, "Agent Keyboard/models", 0, walk)
        }
        val trees = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }
        for (tree in trees) {
            if (walk.exhausted) break
            try {
                val docId = DocumentsContract.getTreeDocumentId(tree)
                walkTree(tree, docId, treeLabel(tree), 0, walk)
            } catch (e: RuntimeException) { // revoked grant, provider gone
                AkLog.w("scan: folder unavailable")
            }
        }
        val all = allFilesOption && Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
        if (all) {
            val storage = Environment.getExternalStorageDirectory()
            // The usual places first, so a time-boxed walk finds them before anything else.
            val first = listOf(Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS).map { File(storage, it) }
            val rest = storage.listFiles()?.filter { it.isDirectory && it !in first && it.name != "Android" && !it.name.startsWith(".") }.orEmpty()
            for (dir in first + rest) {
                if (walk.exhausted) break
                walkFiles(dir, dir.name, 1, walk)
            }
        }
        if (walk.exhausted) AkLog.d("scan: stopped at its budget (${walk.visited} entries)")
        val access = when {
            all -> ModelService.ACCESS_ALL
            trees.isNotEmpty() -> ModelService.ACCESS_FOLDERS
            else -> ModelService.ACCESS_APP
        }
        return ScanResult(walk.out, access, trees.size, allFilesOption)
    }

    override fun open(ref: String): InputStream = when {
        ref.startsWith(FILE_REF) -> FileInputStream(File(ref.removePrefix(FILE_REF)))
        ref.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(ref)) ?: throw IOException("provider returned nothing")
        else -> throw IOException("unknown reference")
    }

    private fun walkFiles(dir: File, where: String, depth: Int, walk: Walk) {
        if (depth > MAX_DEPTH || walk.exhausted) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (walk.exhausted) return
            walk.visited++
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) walkFiles(f, "$where/${f.name}", depth + 1, walk)
            else if (candidate(f.name, f.length())) walk.out += FoundFile(FILE_REF + f.absolutePath, f.name, f.length(), dir.absolutePath, where)
        }
    }

    private fun walkTree(tree: Uri, parentId: String, where: String, depth: Int, walk: Walk) {
        if (depth > MAX_DEPTH || walk.exhausted) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        )
        val dirs = ArrayList<Pair<String, String>>()
        context.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (walk.exhausted) break
                walk.visited++
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (name.startsWith(".")) continue
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) dirs += id to name
                else {
                    val size = if (c.isNull(3)) -1L else c.getLong(3)
                    if (candidate(name, size)) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        walk.out += FoundFile(uri.toString(), name, size, "$tree#$parentId", where)
                    }
                }
            }
        }
        for ((id, name) in dirs) walkTree(tree, id, "$where/$name", depth + 1, walk)
    }

    /** "primary:Download/models" → "Download/models". */
    private fun treeLabel(tree: Uri): String =
        DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifEmpty { "Phone storage" }

    private fun candidate(name: String, size: Long): Boolean {
        if (size <= 0) return false
        val lower = name.lowercase()
        return lower.endsWith(".litertlm") || lower.endsWith(".onnx") || lower == "tokens.txt"
    }

    private companion object {
        const val FILE_REF = "file:"
        const val MAX_DEPTH = 5
        const val MAX_ENTRIES = 20_000
        const val MAX_RESULTS = 500
        const val BUDGET_MS = 4_000L
    }
}
