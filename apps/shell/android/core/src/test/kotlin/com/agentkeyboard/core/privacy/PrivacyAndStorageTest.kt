package com.agentkeyboard.core.privacy

import com.agentkeyboard.core.context.AppCategories
import com.agentkeyboard.core.context.ConversationCache
import com.agentkeyboard.core.device.DeviceTiers
import com.agentkeyboard.core.protocol.AgentDefinition
import com.agentkeyboard.core.protocol.AutonomyLevel
import com.agentkeyboard.core.protocol.ChatMessage
import com.agentkeyboard.core.protocol.DeviceTier
import com.agentkeyboard.core.protocol.GENERAL_AGENT_ID
import com.agentkeyboard.core.protocol.validateAgent
import com.agentkeyboard.core.storage.Migrations
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PrivacyFilterTest {
    @Test fun blocksPresetAndFinanceAppsButHonoursUserOverrides() {
        assertTrue(PrivacyFilter.isBlockedApp("com.x8bit.bitwarden"))
        assertTrue(PrivacyFilter.isBlockedApp("com.ecobank.mobile"))
        assertTrue(PrivacyFilter.isBlockedApp("com.example.banking"))
        assertTrue(PrivacyFilter.isBlockedApp("com.example.notes", userBlocked = setOf("com.example.notes")))
        assertFalse(PrivacyFilter.isBlockedApp("com.ecobank.mobile", userAllowed = setOf("com.ecobank.mobile")))
        assertFalse(PrivacyFilter.isBlockedApp("com.whatsapp"))
        assertFalse(PrivacyFilter.isBlockedApp("com.google.android.apps.messaging"))
        assertFalse(PrivacyFilter.isBlockedApp(""))
    }

    @Test fun redactsLuhnValidCardsOnly() {
        assertEquals("pay with [card] today", PrivacyFilter.redact("pay with 4111 1111 1111 1111 today"))
        assertEquals("[card]", PrivacyFilter.redact("5500-0000-0000-0004"))
        val notACard = "order 1234 5678 9012 3456" // fails Luhn
        assertEquals(notACard, PrivacyFilter.redact(notACard))
        assertEquals("call 0244123456", PrivacyFilter.redact("call 0244123456"), "phone numbers are not cards")
    }

    @Test fun redactsOneTimeCodesNearKeywords() {
        assertEquals("Your verification code is [code].", PrivacyFilter.redact("Your verification code is 482913."))
        assertEquals("[code] is your OTP", PrivacyFilter.redact("482913 is your OTP"))
        assertEquals("Meet at 1430 near the mall", PrivacyFilter.redact("Meet at 1430 near the mall"))
        assertTrue(PrivacyFilter.containsSecret("G-123456 is your Google verification code"))
    }

    @Test fun redactionIsIdempotent() {
        val once = PrivacyFilter.redact("code 123456 and card 4111111111111111")
        assertEquals(once, PrivacyFilter.redact(once))
    }

    @Test fun nodeAdmission() {
        assertTrue(PrivacyFilter.admitNode(isPassword = false, inSecureWindow = false))
        assertFalse(PrivacyFilter.admitNode(isPassword = true, inSecureWindow = false))
        assertFalse(PrivacyFilter.admitNode(isPassword = false, inSecureWindow = true))
    }
}

class ConversationCacheTest {
    private var now = 10_000_000L
    private val cache = ConversationCache(maxConversations = 2, maxMessages = 3, ttlMs = 1_000_000, clock = { now })

    private fun msg(i: Int, text: String = "m$i") = ChatMessage("$i", false, "Kofi", text, i.toLong())

    @Test fun mergesDedupesBoundsAndRedacts() {
        cache.record("com.whatsapp", "Kofi", listOf(msg(1), msg(2)))
        val c = cache.record("com.whatsapp", "Kofi ", listOf(msg(2), msg(3), msg(4, "code 123456 for login")))!!
        assertEquals(listOf("m2", "m3", "code [code] for login"), c.messages.map { it.text })
        assertEquals(c, cache.latestFor("com.whatsapp"))
    }

    @Test fun lruEvictionAndTtl() {
        cache.record("a", "1", listOf(msg(1)))
        cache.record("b", "1", listOf(msg(1)))
        cache.record("c", "1", listOf(msg(1)))
        assertEquals(null, cache.get(ConversationCache.keyOf("a", "1")))
        now += 2_000_000
        assertEquals(null, cache.latestFor("c"))
    }

    @Test fun blockedAppsAreNeverCached() {
        assertEquals(null, cache.record("com.x8bit.bitwarden", "Vault", listOf(msg(1))))
    }

    @Test fun categories() {
        assertEquals("chat", AppCategories.of("com.whatsapp"))
        assertEquals("email", AppCategories.of("com.google.android.gm"))
        assertEquals("other", AppCategories.of("com.unknown"))
        assertEquals("chat", AppCategories.screenTypeFor("chat"))
    }
}

class DeviceTierTest {
    private val gb = 1024L * 1024 * 1024

    @Test fun marketedSizesLandInTheRightTier() {
        assertEquals(DeviceTier.S, DeviceTiers.fromTotalMem((3.6 * gb).toLong()), "4 GB phone")
        assertEquals(DeviceTier.M, DeviceTiers.fromTotalMem((5.7 * gb).toLong()), "6 GB phone")
        assertEquals(DeviceTier.M, DeviceTiers.fromTotalMem((7.5 * gb).toLong()), "8 GB phone")
        assertEquals(DeviceTier.L, DeviceTiers.fromTotalMem((11.2 * gb).toLong()), "12 GB phone")
        assertEquals(DeviceTier.S, DeviceTiers.fromTotalMem(16 * gb, isLowRamDevice = true))
    }

    @Test fun budgetsMatchSpec() {
        val s = DeviceTiers.budget(DeviceTier.S)
        assertEquals(90, s.imeSteadyMb); assertEquals(1_100, s.agentPeakMb)
        assertEquals(1_600, DeviceTiers.budget(DeviceTier.M).agentPeakMb)
    }
}

class MigrationsTest {
    @Test fun filesAreContiguousAndLoadable() {
        Migrations.FILES.forEachIndexed { i, f ->
            assertEquals(i + 1, Migrations.versionOf(f), "migration $f out of order")
            assertTrue(Migrations.load(f).isNotBlank())
        }
        assertEquals(Migrations.FILES, Migrations.pending(0))
        assertEquals(emptyList(), Migrations.pending(Migrations.LATEST_VERSION))
        assertFailsWith<IllegalArgumentException> { Migrations.pending(Migrations.LATEST_VERSION + 1) }
    }

    @Test fun splitterKeepsTriggerBodiesIntact() {
        val sql = """
            -- comment; with semicolon
            CREATE TABLE t (a TEXT DEFAULT 'x;y');
            CREATE TRIGGER tr AFTER INSERT ON t BEGIN
              INSERT INTO u VALUES (1);
              INSERT INTO u VALUES (2);
            END;
            CREATE INDEX i ON t(a)
        """.trimIndent()
        val st = Migrations.statements(sql)
        assertEquals(3, st.size)
        assertTrue(st[0].contains("'x;y'"))
        assertTrue(st[1].startsWith("CREATE TRIGGER") && st[1].trimEnd().endsWith("END"))
    }

    @Test fun initSchemaHasEverySpecTable() {
        val sql = Migrations.load("0001_init.sql")
        val tables = Regex("""CREATE (?:VIRTUAL )?TABLE (\w+)""").findAll(sql).map { it.groupValues[1] }.toSet()
        val spec = setOf(
            "agents", "agent_resources", "chunks", "chunks_fts", "memory_facts", "style_profile", "contacts",
            "conversations", "messages", "autonomy_policies", "audit_log", "clipboard_items", "notes",
            "profile_vault", "model_registry", "settings",
        )
        assertEquals(spec, tables)
    }
}

class AgentValidationTest {
    private fun agent(name: String = "Sales Bot", role: String = "Sell.", icon: String = "SB", id: String = "a1", kind: String = "custom") = AgentDefinition(
        id, kind, name, icon, "", role, "friendly", listOf("en"), emptyList(), emptyList(), null,
        AutonomyLevel.DRAFT, true, 0, 0, 1,
    )

    @Test fun mirrorsShellValidation() {
        assertEquals(emptyList(), validateAgent(agent()))
        assertEquals(listOf("Name is required."), validateAgent(agent(name = " ")))
        assertEquals(listOf("Name must be 40 characters or fewer."), validateAgent(agent(name = "x".repeat(41))))
        assertEquals(listOf("Prompt must be ≤ 15000 characters."), validateAgent(agent(role = "x".repeat(15_001))))
        assertEquals(listOf("Icon must be 1–2 characters."), validateAgent(agent(icon = "ABC")))
        assertEquals(emptyList(), validateAgent(agent(icon = "😀")), "one emoji = one code point")
        assertEquals(listOf("The General agent cannot change kind."), validateAgent(agent(id = GENERAL_AGENT_ID)))
    }

    @Test fun appScopeAll() {
        assertTrue(agent().appliesTo("anything"))
        assertNotNull(agent())
    }
}
