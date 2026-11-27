package com.agentkeyboard.core.device

import com.agentkeyboard.core.protocol.DeviceTier

/**
 * Device tiering (SPEC §12.1, ADR-0004). Thresholds use `ActivityManager.MemoryInfo.totalMem`, which
 * reports less than the marketed size (kernel/carve-outs), so a "6 GB" phone reads ≈ 5.6–5.8 GB and
 * a "4 GB" phone ≈ 3.6–3.8 GB. The 5.5 GB / 9 GB cut-points sit between those clusters.
 */
object DeviceTiers {
    private const val GB = 1024L * 1024 * 1024
    const val S_MAX_BYTES = (5.5 * GB).toLong()
    const val M_MAX_BYTES = 9 * GB

    fun fromTotalMem(totalMemBytes: Long, isLowRamDevice: Boolean = false): DeviceTier = when {
        isLowRamDevice || totalMemBytes < S_MAX_BYTES -> DeviceTier.S
        totalMemBytes < M_MAX_BYTES -> DeviceTier.M
        else -> DeviceTier.L
    }

    /** Memory budgets the host enforces/monitors for a tier (NFR-M-01..04), in MB. */
    data class Budget(val imeSteadyMb: Int, val imePeakMb: Int, val agentIdleMb: Int, val agentPeakMb: Int)

    fun budget(tier: DeviceTier): Budget = when (tier) {
        DeviceTier.S -> Budget(90, 120, 40, 1_100)
        DeviceTier.M -> Budget(90, 120, 40, 1_600)
        // SPEC §5.2 sets no Tier L agent budget yet; L runs E2B under the M budget until the bake-off (§19.5).
        DeviceTier.L -> Budget(90, 120, 40, 1_600)
    }
}
