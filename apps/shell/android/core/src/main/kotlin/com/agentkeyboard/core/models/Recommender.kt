package com.agentkeyboard.core.models

import com.agentkeyboard.core.protocol.WireEnum
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Which model each phone should run (ADR-0012). The smartest model that runs *smoothly* on this
 * device, decided from the real hardware rather than a fixed tier table:
 *
 *   fits?   peak RAM of the model vs. what Android leaves a background-capable app on this phone
 *   fast?   decode speed: measured on this phone once the model has run, estimated before that
 *   smart?  the catalog's `quality` (0–100, from public benchmarks) breaks ties upward
 *
 * Pure and deterministic, so the Models screen, "Download recommended" and the engine's choice of
 * active model can never disagree.
 */

/** What the host measured about this phone. */
data class DeviceSpecs(
    val totalRamMb: Int,
    val cpuCores: Int,
    /** An OpenCL GPU driver is present (LiteRT-LM's GPU backend can be tried). */
    val gpu: Boolean,
    /** `ActivityManager.isLowRamDevice`: Android Go and similar; keep models small. */
    val lowRam: Boolean = false,
    /** Marketing-free SoC id (`Build.SOC_MODEL`, API 31+), shown on the Models screen. */
    val soc: String? = null,
)

enum class Fit(override val wire: String) : WireEnum {
    /** The pick for this phone: smartest model that fits comfortably and runs at a usable speed. */
    RECOMMENDED("recommended"),
    /** Runs well; not the pick (less capable, or a different trade-off). */
    GOOD("good"),
    /** Fits, but will be slow or squeeze other apps out of memory. Allowed, with a warning. */
    SLOW("slow"),
    /** Peak memory exceeds what this phone can give it; downloading is refused. */
    TOO_BIG("too_big"),
}

data class Advice(val fit: Fit, val detail: String, val estTokensPerSec: Double?)

object Recommender {
    /** Below this decode rate replies feel sluggish while typing (SPEC NFR-P-08, Tier S floor). */
    const val MIN_TOKENS_PER_SEC = 10.0

    /**
     * Memory a model may comfortably use: the keyboard runs next to the app the user is typing in, so
     * the model must leave the foreground app and Android itself ~2.5 GB. Low-RAM devices get a quarter.
     */
    fun comfortableMb(d: DeviceSpecs): Int =
        if (d.lowRam) d.totalRamMb / 4 else min((d.totalRamMb * 0.4).roundToInt(), d.totalRamMb - 2_500).coerceAtLeast(600)

    /** Past this, Android's low-memory killer is likely to end the model (or the user's app) mid-reply. */
    fun hardLimitMb(d: DeviceSpecs): Int = if (d.lowRam) d.totalRamMb / 3 else min((d.totalRamMb * 0.55).roundToInt(), d.totalRamMb - 1_800)

    /**
     * Decode speed estimate for a generative model. Decode is memory-bandwidth bound, so tokens/s
     * scales with 1 / bytes touched per token (~ the model's working set). The device factor is
     * calibrated on Gemma 3 1B ≈ 20 tok/s CPU on a 2023 mid-range phone; a usable GPU adds ~40 %.
     */
    fun estimateTokensPerSec(e: ModelEntry, d: DeviceSpecs): Double {
        var factor = 18.0 // tok/s × GB of working set
        if (d.totalRamMb >= 11_000 && d.cpuCores >= 8) factor *= 1.35 // flagship memory + big cores
        else if (d.totalRamMb >= 7_000) factor *= 1.15 // "8 GB" phones report ~7.3 GB
        else if (d.totalRamMb < 5_000) factor *= 0.7 // entry-level LPDDR4X, few big cores
        if (d.gpu && Backend.GPU in e.backends) factor *= 1.4
        return factor / (e.ramMb / 1_000.0).coerceAtLeast(0.2)
    }

    /**
     * Advice for every entry. [measured] holds decode tokens/s observed on this phone (it replaces the
     * estimate). Custom (unverified) models are judged but never recommended.
     */
    fun advise(entries: List<ModelEntry>, d: DeviceSpecs, measured: Map<String, Double> = emptyMap()): Map<String, Advice> {
        val comfortable = comfortableMb(d)
        val hard = hardLimitMb(d)
        val out = HashMap<String, Advice>()
        // Pick per job: generative models compete with each other, speech models with speech models.
        for ((_, group) in entries.groupBy { if (it.generative) "gen" else it.kind.wire }) {
            val speed = group.associate { e -> e.id to if (e.generative) measured[e.id] ?: estimateTokensPerSec(e, d) else null }
            fun fast(e: ModelEntry) = speed[e.id]?.let { it >= MIN_TOKENS_PER_SEC } ?: true
            val candidates = group.filter { !it.custom && it.ramMb <= hard }
            val smooth = candidates.filter { it.ramMb <= comfortable && fast(it) }
            // Smartest smooth model; if nothing is smooth, the lightest model that fits at all.
            val pick = smooth.maxWithOrNull(compareBy<ModelEntry> { it.quality }.thenBy { -it.ramMb })
                ?: candidates.minByOrNull { it.ramMb }
            for (e in group) {
                val tps = speed[e.id]
                val words = tps?.let { " (about ${(it * 0.75).roundToInt().coerceAtLeast(1)} words a second${if (e.id in measured) "" else ", estimated"})" } ?: ""
                out[e.id] = when {
                    e.ramMb > hard -> Advice(Fit.TOO_BIG, "Needs more memory than this phone can spare (for phones with ${marketedGb(ramNeededMb(e))}+ RAM)", tps)
                    e.id == pick?.id && (e.ramMb <= comfortable && fast(e)) ->
                        Advice(Fit.RECOMMENDED, "Best for this phone: the smartest ${noun(e)} that runs smoothly with ${marketedGb(d.totalRamMb)} of RAM$words", tps)
                    e.id == pick?.id -> Advice(Fit.RECOMMENDED, "The lightest ${noun(e)} that fits this phone; expect slower replies$words", tps)
                    e.ramMb > comfortable -> Advice(Fit.SLOW, "Fits, but may close other apps while it works$words", tps)
                    !fast(e) -> Advice(Fit.SLOW, "Smarter, but slow on this phone$words", tps)
                    e.custom -> Advice(Fit.GOOD, "Unverified model from this phone$words", tps)
                    pick != null && e.quality < pick.quality -> Advice(Fit.GOOD, "Faster and lighter, less capable than ${pick.name}$words", tps)
                    else -> Advice(Fit.GOOD, "Runs well on this phone$words", tps)
                }
            }
        }
        return out
    }

    private fun noun(e: ModelEntry) = if (e.kind == ModelKind.STT) "voice model" else "model"

    /** Smallest phone RAM for which [hardLimitMb] admits [e]. */
    fun ramNeededMb(e: ModelEntry): Int = maxOf((e.ramMb / 0.55).roundToInt(), e.ramMb + 1_800)

    /**
     * RAM as sold: Android reports less than the box says (kernel and carve-outs), so a "8 GB" phone
     * shows ~7.3 GB. Rounds up to the next common size.
     */
    fun marketedGb(mb: Int): String {
        val sizes = intArrayOf(2, 3, 4, 6, 8, 12, 16, 24)
        val g = mb / 1024.0
        return "${sizes.firstOrNull { it >= g - 0.05 } ?: g.roundToInt()} GB"
    }
}
