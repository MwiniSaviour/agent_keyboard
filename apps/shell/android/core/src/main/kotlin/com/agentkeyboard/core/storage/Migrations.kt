package com.agentkeyboard.core.storage

/**
 * Forward-only schema migrations (SPEC §16). SQL lives in `resources/db/migrations/NNNN_name.sql`;
 * this object only orders them and splits statements. The same files are applied to a real SQLite
 * (with FTS5) by `tools/verify-schema.py` in CI, and by SQLCipher on device.
 */
object Migrations {
    /** Ordered list of shipped migrations. Append only. */
    val FILES: List<String> = listOf("0001_init.sql")

    val LATEST_VERSION: Int get() = FILES.size

    fun versionOf(file: String): Int = file.substringBefore('_').toInt()

    /** Migrations to run when upgrading from [from] (0 = fresh install) to [LATEST_VERSION]. */
    fun pending(from: Int): List<String> {
        require(from in 0..LATEST_VERSION) { "cannot migrate from v$from: newer than this build (downgrade unsupported)" }
        return FILES.drop(from)
    }

    fun load(file: String): String =
        Migrations::class.java.getResourceAsStream("/db/migrations/$file")?.bufferedReader()?.use { it.readText() }
            ?: error("missing migration resource $file")

    /**
     * Splits a migration into statements. Handles `--` comments, quoted strings and
     * `CREATE TRIGGER … BEGIN … END;` bodies, which contain semicolons.
     */
    fun statements(sql: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        var inString = false
        var depth = 0 // BEGIN…END nesting inside triggers
        while (i < sql.length) {
            val c = sql[i]
            if (!inString && c == '-' && sql.startsWith("--", i)) {
                while (i < sql.length && sql[i] != '\n') i++
                continue
            }
            if (c == '\'') inString = !inString
            if (!inString && (c.isLetter())) {
                val start = i
                while (i < sql.length && (sql[i].isLetterOrDigit() || sql[i] == '_')) i++
                val word = sql.substring(start, i)
                when (word.uppercase()) {
                    "BEGIN" -> depth++
                    "END" -> if (depth > 0) depth--
                }
                cur.append(word)
                continue
            }
            if (!inString && c == ';' && depth == 0) {
                cur.toString().trim().takeIf { it.isNotEmpty() }?.let(out::add)
                cur.setLength(0)
            } else {
                cur.append(c)
            }
            i++
        }
        cur.toString().trim().takeIf { it.isNotEmpty() }?.let(out::add)
        return out
    }
}
