package com.agentkeyboard.core.models

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecommenderTest {
    // Catalog-shaped entries: peak RAM and quality as in models/catalog.json.
    private val g3 = entryFor(byteArrayOf(1), id = "g3", ramMb = 900, quality = 40).copy(backends = listOf(Backend.GPU, Backend.CPU))
    private val e2b = entryFor(byteArrayOf(2), id = "e2b", ramMb = 1_500, quality = 70).copy(backends = listOf(Backend.GPU, Backend.CPU), name = "Gemma 4 E2B")
    private val e4b = entryFor(byteArrayOf(3), id = "e4b", ramMb = 2_400, quality = 80).copy(backends = listOf(Backend.GPU, Backend.CPU))
    private val zip = entryFor(byteArrayOf(4), id = "zip", ramMb = 120, quality = 55, kind = ModelKind.STT)
    private val kroko = entryFor(byteArrayOf(5), id = "kroko", ramMb = 90, quality = 65, kind = ModelKind.STT)
    private val all = listOf(g3, e2b, e4b, zip, kroko)

    private fun fits(d: DeviceSpecs, measured: Map<String, Double> = emptyMap()) =
        Recommender.advise(all, d, measured).mapValues { it.value.fit }

    @Test fun eachPhoneGetsTheSmartestModelThatRunsSmoothly() {
        // 3 GB: nothing fits comfortably; the lightest that fits at all is still offered.
        assertEquals(mapOf("g3" to Fit.RECOMMENDED, "e2b" to Fit.TOO_BIG, "e4b" to Fit.TOO_BIG, "zip" to Fit.GOOD, "kroko" to Fit.RECOMMENDED), fits(Phones.SMALL))
        // 6 GB, CPU only: E2B fits and is fast enough; E4B fits but would be slow and crowd memory.
        assertEquals(Fit.RECOMMENDED, fits(Phones.MID)["e2b"])
        assertEquals(Fit.GOOD, fits(Phones.MID)["g3"])
        assertEquals(Fit.SLOW, fits(Phones.MID)["e4b"])
        // 8 GB + GPU: E2B still the pick (E4B's 2.4 GB exceeds the comfortable 40 %… of 7.4 GB = 2.96 GB? no: fits)
        val big = Recommender.advise(all, Phones.BIG)
        assertEquals(Fit.RECOMMENDED, big.getValue("e4b").fit, big.getValue("e4b").detail)
        assertEquals(Fit.GOOD, big.getValue("e2b").fit)
        // 12 GB flagship: E4B, and E2B is a faster alternative.
        val flagship = DeviceSpecs(totalRamMb = 11_500, cpuCores = 8, gpu = true)
        assertEquals(Fit.RECOMMENDED, fits(flagship)["e4b"])
        assertTrue(Recommender.advise(all, flagship).getValue("e2b").detail.startsWith("Faster and lighter"))
    }

    @Test fun speechAndTextArePickedIndependently() {
        val a = Recommender.advise(all, Phones.MID)
        assertEquals(1, a.filterKeys { it in setOf("zip", "kroko") }.count { it.value.fit == Fit.RECOMMENDED })
        assertEquals(1, a.filterKeys { it in setOf("g3", "e2b", "e4b") }.count { it.value.fit == Fit.RECOMMENDED })
        assertEquals(Fit.RECOMMENDED, a.getValue("kroko").fit)
        assertNull(a.getValue("kroko").estTokensPerSec) // no decode speed for speech models
        assertTrue(a.getValue("kroko").detail.contains("voice model"))
    }

    @Test fun measuredSpeedOverridesTheEstimate() {
        assertEquals(Fit.RECOMMENDED, fits(Phones.MID)["e2b"])
        val slowPhone = fits(Phones.MID, mapOf("e2b" to 7.0))
        assertEquals(Fit.SLOW, slowPhone["e2b"])
        assertEquals(Fit.RECOMMENDED, slowPhone["g3"])
        val a = Recommender.advise(all, Phones.MID, mapOf("g3" to 30.0))
        assertTrue(a.getValue("g3").detail.endsWith("(about 23 words a second)"), a.getValue("g3").detail)
        assertTrue(a.getValue("e2b").detail.endsWith("estimated)"))
    }

    @Test fun lowRamDevicesStayConservative() {
        val go = DeviceSpecs(totalRamMb = 3_800, cpuCores = 4, gpu = false, lowRam = true)
        assertEquals(950, Recommender.comfortableMb(go))
        val f = fits(go)
        assertEquals(Fit.RECOMMENDED, f["g3"])
        assertEquals(Fit.TOO_BIG, f["e2b"])
    }

    @Test fun customModelsAreJudgedButNeverRecommended() {
        val custom = e2b.copy(id = "custom-x", custom = true, quality = 99)
        val a = Recommender.advise(listOf(g3, custom), Phones.BIG)
        assertEquals(Fit.RECOMMENDED, a.getValue("g3").fit)
        assertEquals(Fit.GOOD, a.getValue("custom-x").fit)
        assertTrue(a.getValue("custom-x").detail.startsWith("Unverified model from this phone"))
        val huge = custom.copy(ramMb = 9_000)
        assertEquals(Fit.TOO_BIG, Recommender.advise(listOf(huge), Phones.BIG).getValue("custom-x").fit)
    }

    @Test fun ramIsShownAsSoldAndTooBigSaysWhatItNeeds() {
        assertEquals("3 GB", Recommender.marketedGb(2_800))
        assertEquals("4 GB", Recommender.marketedGb(3_650))
        assertEquals("6 GB", Recommender.marketedGb(5_600))
        assertEquals("8 GB", Recommender.marketedGb(7_400))
        assertEquals("12 GB", Recommender.marketedGb(11_500))
        assertEquals(3_300, Recommender.ramNeededMb(e2b))
        assertEquals("Needs more memory than this phone can spare (for phones with 4 GB+ RAM)", Recommender.advise(all, Phones.SMALL).getValue("e2b").detail)
    }
}
