package com.agentkeyboard.core.protocol

import com.agentkeyboard.core.json.JsonEncodable
import com.agentkeyboard.core.json.JsonObj
import com.agentkeyboard.core.json.JsonValue
import com.agentkeyboard.core.json.arr
import com.agentkeyboard.core.json.arrOrNull
import com.agentkeyboard.core.json.bool
import com.agentkeyboard.core.json.long
import com.agentkeyboard.core.json.obj
import com.agentkeyboard.core.json.objOrNull
import com.agentkeyboard.core.json.str

/**
 * Kotlin mirror of `packages/protocol/src/domain.ts`. Field names and string enums are identical;
 * the shared fixtures in `packages/protocol/fixtures/` keep both sides honest (SPEC §19.1).
 *
 * Enums carry their wire value explicitly so a Kotlin rename can never change the protocol.
 */

interface WireEnum { val wire: String }

inline fun <reified E> wireOf(value: String?): E? where E : Enum<E>, E : WireEnum =
    enumValues<E>().firstOrNull { it.wire == value }

enum class InputKind(override val wire: String) : WireEnum {
    TEXT("text"), MULTILINE("multiline"), EMAIL("email"), URL("url"), PHONE("phone"),
    NUMBER("number"), SEARCH("search"), NAME("name"), PASSWORD("password"), OTP("otp"),
}

enum class ImeActionKind(override val wire: String) : WireEnum {
    NONE("none"), SEND("send"), SEARCH("search"), GO("go"), NEXT("next"), DONE("done"), NEWLINE("newline"),
}

enum class CapsMode(override val wire: String) : WireEnum {
    NONE("none"), SENTENCES("sentences"), WORDS("words"), CHARACTERS("characters"),
}

enum class AutonomyLevel(override val wire: String) : WireEnum {
    OFF("off"), SUGGEST("suggest"), DRAFT("draft"), AUTO("auto");

    /** Ceiling semantics (FR-AU-01): the effective level is the lower of requested and ceiling. */
    fun cappedBy(ceiling: AutonomyLevel): AutonomyLevel = if (ordinal <= ceiling.ordinal) this else ceiling
}

enum class AutonomyPhase(override val wire: String) : WireEnum {
    OFF("off"), ARMING("arming"), OBSERVING("observing"), PLANNING("planning"), REVIEW("review"),
    TYPING("typing"), AWAITING_USER("awaiting_user"), SENDING("sending"), COOLDOWN("cooldown"),
    PAUSED("paused"), ESCALATED("escalated"),
}

enum class DeviceTier(override val wire: String) : WireEnum { S("S"), M("M"), L("L") }

data class FieldContext(
    val packageName: String,
    val appLabel: String,
    val kind: InputKind,
    val imeAction: ImeActionKind,
    val capsMode: CapsMode,
    val hint: String? = null,
    val label: String? = null,
    val autofillHints: List<String>? = null,
    val sensitive: Boolean,
) : JsonEncodable {
    override fun toJson(): JsonObj = obj(
        "package" to packageName,
        "appLabel" to appLabel,
        "kind" to kind.wire,
        "imeAction" to imeAction.wire,
        "capsMode" to capsMode.wire,
        "hint" to hint,
        "label" to label,
        "autofillHints" to autofillHints,
        "sensitive" to sensitive,
    )

    companion object {
        /** Used before the first `onStartInput` and for the fallback keyboard. */
        val EMPTY = FieldContext("", "", InputKind.TEXT, ImeActionKind.NONE, CapsMode.NONE, sensitive = false)

        /**
         * Strict decode of [toJson] output (IME → `:agent` over AIDL). Unknown enum wires reject the
         * whole object; `sensitive` defaults to **true** when absent so a malformed record can never
         * unlock AI features in a secure field.
         */
        fun fromJson(v: JsonValue?): FieldContext? {
            val o = v.objOrNull() ?: return null
            val pkg = o["package"].str()?.takeIf { it.length <= 256 } ?: return null
            return FieldContext(
                packageName = pkg,
                appLabel = o["appLabel"].str()?.take(128) ?: "",
                kind = wireOf<InputKind>(o["kind"].str()) ?: return null,
                imeAction = wireOf<ImeActionKind>(o["imeAction"].str()) ?: return null,
                capsMode = wireOf<CapsMode>(o["capsMode"].str()) ?: return null,
                hint = o["hint"].str()?.take(256),
                label = o["label"].str()?.take(256),
                autofillHints = o["autofillHints"].arrOrNull()?.items?.mapNotNull { it.str() }?.take(16),
                sensitive = o["sensitive"].bool() ?: true,
            )
        }
    }
}

data class EditorState(val before: String, val after: String, val selected: String) : JsonEncodable {
    override fun toJson(): JsonObj = obj("before" to before, "after" to after, "selected" to selected)

    companion object {
        val EMPTY = EditorState("", "", "")
    }
}

data class Capabilities(
    val protocol: Int = PROTOCOL_VERSION,
    val platform: String,
    val tier: DeviceTier,
    val nativeSuggestions: Boolean,
    val voice: Boolean,
    val tts: Boolean,
    val screenContext: Boolean,
    val visualContext: Boolean,
    val autonomy: Boolean,
    val screenCapture: Boolean,
    val screenScan: Boolean,
) : JsonEncodable {
    override fun toJson(): JsonObj = obj(
        "protocol" to protocol, "platform" to platform, "tier" to tier.wire,
        "nativeSuggestions" to nativeSuggestions, "voice" to voice, "tts" to tts,
        "screenContext" to screenContext, "visualContext" to visualContext, "autonomy" to autonomy,
        "screenCapture" to screenCapture, "screenScan" to screenScan,
    )
}

data class SystemStatus(
    val imeEnabled: Boolean,
    val imeSelected: Boolean,
    val notificationAccess: Boolean,
    val screenReading: Boolean,
    val flavor: String,
    val tier: DeviceTier,
    val totalRamMb: Int,
    val modelsInstalled: Boolean,
    val autonomyConsent: Boolean = false,
) : JsonEncodable {
    override fun toJson(): JsonObj = obj(
        "imeEnabled" to imeEnabled, "imeSelected" to imeSelected,
        "notificationAccess" to notificationAccess, "screenReading" to screenReading,
        "flavor" to flavor, "tier" to tier.wire, "totalRamMb" to totalRamMb, "modelsInstalled" to modelsInstalled,
        "autonomyConsent" to autonomyConsent,
    )
}

enum class SystemSettingsTarget(override val wire: String) : WireEnum {
    IME_SETTINGS("ime_settings"), IME_PICKER("ime_picker"),
    NOTIFICATION_ACCESS("notification_access"), ACCESSIBILITY("accessibility"),
}

data class ClipboardItem(val id: String, val text: String, val pinned: Boolean, val at: Long) : JsonEncodable {
    override fun toJson(): JsonObj = obj("id" to id, "text" to text, "pinned" to pinned, "at" to at)
}

data class Suggestion(val text: String, val kind: String, val primary: Boolean? = null) : JsonEncodable {
    override fun toJson(): JsonObj = obj("text" to text, "kind" to kind, "primary" to primary)
}

data class QuickReply(val id: String, val title: String, val text: String) : JsonEncodable {
    override fun toJson(): JsonObj = obj("id" to id, "title" to title, "text" to text)
}

data class ResourceRef(val id: String, val name: String, val kind: String, val bytes: Long, val status: String) : JsonEncodable {
    override fun toJson(): JsonObj = obj("id" to id, "name" to name, "kind" to kind, "bytes" to bytes, "status" to status)
}

const val AGENT_ROLE_MAX_CHARS = 15_000
const val AGENT_NAME_MAX_CHARS = 40
const val GENERAL_AGENT_ID = "general"

data class AgentDefinition(
    val id: String,
    val kind: String, // "general" | "custom"
    val name: String,
    val icon: String,
    val tagline: String,
    val role: String,
    val tone: String,
    val languages: List<String>,
    val knowledge: List<ResourceRef>,
    val quickReplies: List<QuickReply>,
    /** `null` means "all" apps. */
    val appScope: List<String>?,
    val autonomyCeiling: AutonomyLevel,
    val shareGeneralMemory: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Int,
) : JsonEncodable {
    override fun toJson(): JsonObj = obj(
        "id" to id, "kind" to kind, "name" to name, "icon" to icon, "tagline" to tagline,
        "role" to role, "tone" to tone, "languages" to languages,
        "knowledge" to knowledge, "quickReplies" to quickReplies,
        "appScope" to (appScope ?: "all"),
        "autonomyCeiling" to autonomyCeiling.wire, "shareGeneralMemory" to shareGeneralMemory,
        "createdAt" to createdAt, "updatedAt" to updatedAt, "version" to version,
    )

    fun appliesTo(packageName: String): Boolean = appScope == null || packageName in appScope

    companion object {
        private val TONES = setOf("neutral", "friendly", "formal", "persuasive", "concise")
        private val RESOURCE_KINDS = setOf("pdf", "txt", "md", "docx", "url", "note")
        private val RESOURCE_STATUS = setOf("pending", "indexed", "failed")

        /** Strict decode; returns null with a reason so the router can answer `bad_payload`. */
        fun fromJson(v: JsonValue?): Pair<AgentDefinition?, String?> {
            val o = v.objOrNull() ?: return null to "agent must be an object"
            fun s(k: String) = o[k].str()
            val id = s("id")?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return null to "id"
            val kind = s("kind")?.takeIf { it == "general" || it == "custom" } ?: return null to "kind"
            val name = s("name") ?: return null to "name"
            val role = s("role") ?: return null to "role"
            val tone = s("tone")?.takeIf { it in TONES } ?: return null to "tone"
            val ceiling = wireOf<AutonomyLevel>(s("autonomyCeiling")) ?: return null to "autonomyCeiling"
            val scopeV = o["appScope"]
            val scope: List<String>? = when {
                scopeV.str() == "all" -> null
                scopeV.arrOrNull() != null -> scopeV.arrOrNull()!!.items.map { it.str() ?: return null to "appScope" }
                else -> return null to "appScope"
            }
            val knowledge = (o["knowledge"].arrOrNull()?.items ?: emptyList()).map { r ->
                val ro = r.objOrNull() ?: return null to "knowledge"
                ResourceRef(
                    ro["id"].str() ?: return null to "knowledge.id",
                    ro["name"].str() ?: return null to "knowledge.name",
                    ro["kind"].str()?.takeIf { it in RESOURCE_KINDS } ?: return null to "knowledge.kind",
                    ro["bytes"].long() ?: 0,
                    ro["status"].str()?.takeIf { it in RESOURCE_STATUS } ?: return null to "knowledge.status",
                )
            }
            val replies = (o["quickReplies"].arrOrNull()?.items ?: emptyList()).map { r ->
                val ro = r.objOrNull() ?: return null to "quickReplies"
                QuickReply(ro["id"].str() ?: return null to "quickReplies.id", ro["title"].str() ?: "", ro["text"].str() ?: "")
            }
            return AgentDefinition(
                id = id, kind = kind, name = name, icon = s("icon") ?: "", tagline = s("tagline") ?: "",
                role = role, tone = tone,
                languages = o["languages"].arrOrNull()?.items?.mapNotNull { it.str() } ?: listOf("en"),
                knowledge = knowledge, quickReplies = replies, appScope = scope,
                autonomyCeiling = ceiling, shareGeneralMemory = o["shareGeneralMemory"].bool() ?: true,
                createdAt = o["createdAt"].long() ?: 0, updatedAt = o["updatedAt"].long() ?: 0,
                version = o["version"].long()?.toInt() ?: 1,
            ) to null
        }
    }
}

/** Mirrors `validateDraft` in apps/shell/src/agents/repository.ts — same rules, same messages. */
fun validateAgent(a: AgentDefinition): List<String> = buildList {
    if (a.name.isBlank()) add("Name is required.")
    if (a.name.length > AGENT_NAME_MAX_CHARS) add("Name must be $AGENT_NAME_MAX_CHARS characters or fewer.")
    if (a.role.isBlank()) add("Prompt is required.")
    if (a.role.length > AGENT_ROLE_MAX_CHARS) add("Prompt must be ≤ $AGENT_ROLE_MAX_CHARS characters.")
    if (a.icon.codePointCount(0, a.icon.length) > 2) add("Icon must be 1–2 characters.")
    if (a.id == GENERAL_AGENT_ID && a.kind != "general") add("The General agent cannot change kind.")
}

data class AgentRunRequest(
    val runId: String,
    val agentId: String,
    val skill: String,
    val target: String,
    val params: JsonObj?,
) : JsonEncodable {
    override fun toJson(): JsonObj = obj("runId" to runId, "agentId" to agentId, "skill" to skill, "target" to target, "params" to params)

    companion object {
        val SKILLS = setOf(
            "complete", "refine", "reply", "explain", "define", "summarize", "translate",
            "fill_form", "describe_image", "transcribe_refine",
        )
        val TARGETS = setOf("field", "selection", "word", "conversation", "screen", "image")
    }
}

data class SensitivityFlags(val secureWindow: Boolean, val sensitiveField: Boolean, val blockedApp: Boolean) : JsonEncodable {
    val any get() = secureWindow || sensitiveField || blockedApp
    override fun toJson(): JsonObj = obj("secureWindow" to secureWindow, "sensitiveField" to sensitiveField, "blockedApp" to blockedApp)
}

data class ChatMessage(val id: String, val fromMe: Boolean, val author: String, val text: String, val at: Long) : JsonEncodable {
    override fun toJson(): JsonObj = obj("id" to id, "from" to if (fromMe) "me" else "them", "author" to author, "text" to text, "at" to at)
}

/** `ScreenContext.chat`. [match] says how sure the host is that this is the chat on screen. */
data class ChatContext(
    val title: String,
    val participants: List<String>,
    val messages: List<ChatMessage>,
    val match: String? = null,
) : JsonEncodable {
    override fun toJson(): JsonObj = obj("title" to title, "participants" to participants, "messages" to arr(messages), "match" to match)

    companion object {
        const val EXACT = "exact"
        const val LIKELY = "likely"
        const val UNCERTAIN = "uncertain"
    }
}

/** An in-memory frame reference (rung 3). [preview] is a small data: URL for the Lens panel. */
data class ScreenImage(val handle: String, val width: Int? = null, val height: Int? = null, val preview: String? = null, val caption: String? = null) : JsonEncodable {
    override fun toJson(): JsonObj = obj("handle" to handle, "caption" to caption, "width" to width, "height" to height, "preview" to preview)
}

data class ScanInfo(val pages: Int, val complete: Boolean, val restored: Boolean) : JsonEncodable {
    override fun toJson(): JsonObj = obj("pages" to pages, "complete" to complete, "restored" to restored)
}

/** What the native hosts produce of `ScreenContext` (rungs 0–3; OCR/VLM fill captions in Part 3). */
data class ScreenContext(
    val appPackage: String,
    val appLabel: String,
    val category: String,
    val screenType: String,
    val field: FieldContext,
    val chat: ChatContext? = null,
    val text: String? = null,
    val textTitle: String? = null,
    val images: List<ScreenImage> = emptyList(),
    val sensitivity: SensitivityFlags,
    val scan: ScanInfo? = null,
    val rung: Int,
    val capturedAt: Long,
    val ttlMs: Long,
) : JsonEncodable {
    override fun toJson(): JsonObj = obj(
        "app" to obj("package" to appPackage, "label" to appLabel, "category" to category),
        "screenType" to screenType,
        "field" to field,
        "chat" to chat,
        "text" to text?.let { obj("title" to textTitle, "body" to it) },
        "images" to images.takeIf { it.isNotEmpty() }?.let { arr(it) },
        "sensitivity" to sensitivity,
        "scan" to scan,
        "rung" to rung,
        "capturedAt" to capturedAt,
        "ttlMs" to ttlMs,
    )
}

// ───────────────────────────── Models (SPEC §12, ADR-0011) ─────────────────────────────

/** `ModelPack` in domain.ts. [kind] and [state] are wire strings. */
data class ModelPack(
    val id: String,
    val name: String,
    val kind: String,
    val role: String,
    val sizeBytes: Long,
    val license: String,
    val licenseUrl: String?,
    val core: Boolean,
    val state: String,
    val receivedBytes: Long,
    val detail: String? = null,
    val updateAvailable: Boolean? = null,
) : JsonEncodable {
    override fun toJson(): JsonObj = obj(
        "id" to id, "name" to name, "kind" to kind, "role" to role, "sizeBytes" to sizeBytes,
        "license" to license, "licenseUrl" to licenseUrl, "core" to core, "state" to state,
        "receivedBytes" to receivedBytes, "detail" to detail, "updateAvailable" to updateAvailable,
    )

    companion object {
        const val AVAILABLE = "available"
        const val QUEUED = "queued"
        const val DOWNLOADING = "downloading"
        const val PAUSED = "paused"
        const val INSTALLED = "installed"
        const val FAILED = "failed"
    }
}

data class ResidentModel(val id: String, val backend: String) : JsonEncodable {
    override fun toJson(): JsonObj = obj("id" to id, "backend" to backend)
}

/** `ModelsStatus` in domain.ts. */
data class ModelsStatus(
    val tier: DeviceTier,
    val source: String,
    val catalog: String,
    val catalogDetail: String?,
    val packs: List<ModelPack>,
    val freeBytes: Long,
    val wifiOnly: Boolean,
    val resident: ResidentModel?,
) : JsonEncodable {
    override fun toJson(): JsonObj = obj(
        "tier" to tier.wire, "source" to source, "catalog" to catalog, "catalogDetail" to catalogDetail,
        "packs" to packs, "freeBytes" to freeBytes, "wifiOnly" to wifiOnly, "resident" to resident,
    )
}
