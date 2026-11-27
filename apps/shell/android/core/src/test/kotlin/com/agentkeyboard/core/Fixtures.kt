package com.agentkeyboard.core

import com.agentkeyboard.core.json.Json
import com.agentkeyboard.core.json.JsonValue
import java.io.File

/** Locates `packages/protocol/fixtures` by walking up from the working directory (Gradle or CLI). */
object Fixtures {
    private val dir: File by lazy {
        System.getProperty("ak.fixtures")?.let { return@lazy File(it) }
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            val f = File(d, "packages/protocol/fixtures")
            if (f.isDirectory) return@lazy f
            d = d.parentFile
        }
        error("packages/protocol/fixtures not found above ${System.getProperty("user.dir")}")
    }

    fun json(name: String): JsonValue = Json.parse(File(dir, name).readText())

    fun text(name: String): String = File(dir, name).readText()
}
