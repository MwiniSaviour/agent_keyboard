package com.agentkeyboard.core.skills

import com.agentkeyboard.core.inference.Priority
import com.agentkeyboard.core.json.str
import com.agentkeyboard.core.models.Capability
import com.agentkeyboard.core.protocol.AgentRunRequest

/**
 * Skill registry (SPEC §11.2): each skill's model capability, default priority, decoding settings,
 * output contract and whether it streams. Context selection and wording live in [PromptBuilder];
 * post-checks in [OutputValidators].
 */
data class SkillSpec(
    val id: String,
    val capability: Capability,
    val priority: Priority,
    /** Output budget given the estimated size of the text being worked on. */
    val maxOutput: (sourceTokens: Int) -> Int,
    val temperature: Double,
    val output: Output,
    /** Stream tokens to the card. Structured skills don't: raw JSON is not for people. */
    val stream: Boolean,
    /** Non-null while a skill needs a pipeline this build doesn't have yet (shown to the user). */
    val unavailable: String? = null,
) {
    enum class Output { TEXT, DEFINITION, AUTONOMY_REPLY }
}

object Skills {
    const val INSTRUCTION_AUTONOMY = "autonomy"
    const val INSTRUCTION_PRECOMPUTE = "precompute"

    private fun rewrite(factor: Double) = { src: Int -> (src * factor + 32).toInt().coerceIn(48, 1_024) }

    private val SPECS: Map<String, SkillSpec> = listOf(
        SkillSpec("complete", Capability.TEXT, Priority.P1, { 40 }, 0.4, SkillSpec.Output.TEXT, stream = true),
        SkillSpec("refine", Capability.TEXT, Priority.P1, rewrite(1.5), 0.3, SkillSpec.Output.TEXT, stream = true),
        SkillSpec("reply", Capability.TEXT, Priority.P1, { 200 }, 0.7, SkillSpec.Output.TEXT, stream = true),
        SkillSpec("explain", Capability.TEXT, Priority.P1, { 220 }, 0.3, SkillSpec.Output.TEXT, stream = true),
        SkillSpec("define", Capability.TEXT, Priority.P1, { 200 }, 0.2, SkillSpec.Output.DEFINITION, stream = false),
        SkillSpec("summarize", Capability.TEXT, Priority.P1, { 200 }, 0.3, SkillSpec.Output.TEXT, stream = true),
        SkillSpec("translate", Capability.TEXT, Priority.P1, rewrite(2.0), 0.2, SkillSpec.Output.TEXT, stream = true),
        SkillSpec("transcribe_refine", Capability.TEXT, Priority.P0, rewrite(1.3), 0.1, SkillSpec.Output.TEXT, stream = false),
        SkillSpec(
            "fill_form", Capability.TEXT, Priority.P1, { 300 }, 0.3, SkillSpec.Output.TEXT, stream = false,
            unavailable = "Form filling arrives with the Profile vault (roadmap phase 5).",
        ),
        SkillSpec(
            "describe_image", Capability.VISION, Priority.P1, { 160 }, 0.3, SkillSpec.Output.TEXT, stream = true,
            unavailable = "Image description arrives with on-demand vision (roadmap phase 4).",
        ),
    ).associateBy { it.id }

    /** Autonomy drafts use the reply skill with a structured {reply, confidence} contract (FR-AU-04). */
    private val AUTONOMY = SPECS.getValue("reply").copy(
        priority = Priority.P2, output = SkillSpec.Output.AUTONOMY_REPLY, stream = false, maxOutput = { 220 }, temperature = 0.5,
    )

    val ids: Set<String> get() = SPECS.keys

    fun of(request: AgentRunRequest): SkillSpec {
        val base = SPECS[request.skill] ?: error("unknown skill ${request.skill}") // decoder already validated it
        val instruction = request.params?.get("instruction").str()
        return when {
            request.skill == "reply" && instruction == INSTRUCTION_AUTONOMY -> AUTONOMY
            instruction == INSTRUCTION_PRECOMPUTE && base.priority == Priority.P1 -> base.copy(priority = Priority.P3)
            else -> base
        }
    }
}
