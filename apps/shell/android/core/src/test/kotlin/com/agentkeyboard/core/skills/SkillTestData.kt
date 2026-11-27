package com.agentkeyboard.core.skills

import com.agentkeyboard.core.agent.InferenceJob
import com.agentkeyboard.core.agent.RunContext
import com.agentkeyboard.core.context.ConversationCache
import com.agentkeyboard.core.json.JsonObj
import com.agentkeyboard.core.json.obj
import com.agentkeyboard.core.protocol.AgentDefinition
import com.agentkeyboard.core.protocol.AgentRunRequest
import com.agentkeyboard.core.protocol.AutonomyLevel
import com.agentkeyboard.core.protocol.ChatMessage
import com.agentkeyboard.core.protocol.GENERAL_AGENT_ID

object SkillTestData {
    val agent = AgentDefinition(
        id = GENERAL_AGENT_ID, kind = "general", name = "Kofi", icon = "sparkles", tagline = "", role = "Helps with chats for a phone shop in Kumasi.",
        tone = "friendly", languages = listOf("en"), knowledge = emptyList(), quickReplies = emptyList(), appScope = null,
        autonomyCeiling = AutonomyLevel.DRAFT, shareGeneralMemory = true, createdAt = 0, updatedAt = 0, version = 1,
    )

    fun editor(before: String = "", selected: String = "", after: String = ""): JsonObj =
        obj("editor" to obj("before" to before, "selected" to selected, "after" to after))

    fun job(skill: String, params: JsonObj? = null, target: String = "field", conversation: ConversationCache.Conversation? = null, screen: String? = null) =
        InferenceJob(AgentRunRequest("r1", GENERAL_AGENT_ID, skill, target, params), agent, RunContext(null, conversation, screen, if (screen != null) "a11y" else null))

    fun chat(vararg lines: Pair<Boolean, String>) = ConversationCache.Conversation(
        key = "wa:ama", packageName = "com.whatsapp", title = "Ama",
        messages = lines.mapIndexed { i, (me, text) -> ChatMessage("m$i", me, if (me) "" else "Ama", text, i.toLong()) }, updatedAt = 0,
    )
}
