package com.agentkeyboard.app.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.agentkeyboard.app.AkLog
import com.agentkeyboard.core.agent.AgentStore
import com.agentkeyboard.core.agent.AuditRecord
import com.agentkeyboard.core.agent.AuditStore
import com.agentkeyboard.core.agent.ClipboardStore
import com.agentkeyboard.core.agent.SettingsStore
import com.agentkeyboard.core.agent.StoredClip
import com.agentkeyboard.core.json.Json
import com.agentkeyboard.core.json.JsonValue
import com.agentkeyboard.core.protocol.AgentDefinition
import com.agentkeyboard.core.protocol.ClipboardItem
import com.agentkeyboard.core.storage.Migrations
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import java.io.File

/**
 * The single encrypted store (ADR-0007, SPEC §16), opened only in `:agent`. Schema comes from the
 * shared forward-only migrations in `:core` (`resources/db/migrations`), which CI also applies to a
 * real SQLite in `tools/verify-schema.py`.
 */
class AkDatabase private constructor(context: Context, key: ByteArray) : SQLiteOpenHelper(
    context, NAME, key, null, Migrations.LATEST_VERSION, 0, null, null, true,
) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) = migrate(db, 0)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = migrate(db, oldVersion)

    // Downgrades are unsupported by design (forward-only); the framework default throws, which is right.

    /** The open helper already wraps onCreate/onUpgrade in one transaction. */
    private fun migrate(db: SQLiteDatabase, from: Int) {
        for (file in Migrations.pending(from)) {
            Migrations.statements(Migrations.load(file)).forEach(db::execSQL)
            AkLog.i("db migrated → v${Migrations.versionOf(file)}")
        }
    }

    companion object {
        private const val NAME = "agent.db"

        /** Call on a background thread: the first open runs migrations. */
        fun open(context: Context): AkDatabase {
            System.loadLibrary("sqlcipher")
            val (key, fresh) = KeyVault(context).databaseKey()
            if (fresh) {
                // A new key can never open an old file; remove it rather than crash-loop.
                listOf("", "-wal", "-shm", "-journal").forEach { File(context.getDatabasePath(NAME).path + it).delete() }
            }
            // Not zeroed: SQLiteOpenHelper keeps a reference and (re)opens connections lazily with it.
            return AkDatabase(context, key)
        }
    }
}

// ───────────────────────────── stores (ports from :core) ─────────────────────────────

private inline fun <T> Cursor.mapRows(f: Cursor.() -> T): List<T> = use { c -> buildList { while (c.moveToNext()) add(c.f()) } }

private fun Cursor.str(col: String): String? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }

private fun Cursor.lng(col: String): Long? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }

class SqlAgentStore(private val db: SQLiteDatabase) : AgentStore {
    override fun all(): List<AgentDefinition> =
        db.rawQuery("SELECT definition_json FROM agents", emptyArray<String>()).mapRows {
            AgentDefinition.fromJson(Json.parse(getString(0))).first
        }.filterNotNull()

    override fun put(agent: AgentDefinition) {
        db.insertWithOnConflict(
            "agents", null,
            ContentValues().apply {
                put("id", agent.id); put("kind", agent.kind); put("name", agent.name)
                put("definition_json", Json.write(agent.toJson())); put("autonomy_ceiling", agent.autonomyCeiling.wire)
                put("version", agent.version); put("created_at", agent.createdAt); put("updated_at", agent.updatedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** The `agents_keep_general` trigger refuses General; the runtime never asks. */
    override fun delete(id: String) {
        db.delete("agents", "id = ?", arrayOf(id))
    }
}

class SqlClipboardStore(private val db: SQLiteDatabase) : ClipboardStore {
    override fun all(): List<StoredClip> =
        db.rawQuery("SELECT id, text, pinned, at, expires_at FROM clipboard_items", emptyArray<String>()).mapRows {
            StoredClip(ClipboardItem(str("id")!!, str("text")!!, lng("pinned") == 1L, lng("at")!!), lng("expires_at"))
        }

    override fun put(clip: StoredClip) {
        db.insertWithOnConflict(
            "clipboard_items", null,
            ContentValues().apply {
                put("id", clip.item.id); put("text", clip.item.text); put("pinned", if (clip.item.pinned) 1 else 0)
                put("at", clip.item.at); put("expires_at", clip.expiresAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun delete(ids: Collection<String>) {
        if (ids.isEmpty()) return
        db.beginTransaction()
        try {
            ids.forEach { db.delete("clipboard_items", "id = ?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

class SqlAuditStore(private val db: SQLiteDatabase) : AuditStore {
    override fun append(record: AuditRecord) {
        db.insert(
            "audit_log", null,
            ContentValues().apply {
                put("at", record.at); put("action", record.action); put("agent_id", record.agentId)
                put("package", record.packageName); put("conversation_id", record.conversation)
                put("seen", record.seen); put("sent", record.sent); put("reason", record.reason); put("model", record.model)
            },
        )
    }

    override fun purgeBefore(at: Long): Int = db.delete("audit_log", "at < ?", arrayOf(at.toString()))
}

class SqlSettingsStore(private val db: SQLiteDatabase) : SettingsStore {
    override fun get(key: String): JsonValue? =
        db.rawQuery("SELECT value_json FROM settings WHERE key = ?", arrayOf(key)).mapRows { getString(0) }
            .firstOrNull()?.let { runCatching { Json.parse(it) }.getOrNull() }

    override fun put(key: String, value: JsonValue) {
        db.insertWithOnConflict(
            "settings", null,
            ContentValues().apply { put("key", key); put("value_json", Json.write(value)); put("updated_at", System.currentTimeMillis()) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }
}
