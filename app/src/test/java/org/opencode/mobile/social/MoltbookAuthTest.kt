package org.opencode.mobile.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чистая логика авторизации Moltbook без сети: живой сайт отсюда отдаёт 403,
 * поэтому разбор ответов и валидацию проверяем на зафиксированных примерах из
 * открытой документации. Каждый тест закрывает способ испортить регистрацию
 * молча — например, принять JSON null за ключ или открыть чужую claim-ссылку.
 */
class MoltbookAuthTest {
    private companion object {
        const val NAME_MAX = 32
        const val NAME_TOO_LONG = 33
        const val NAME_SAMPLES = 20
    }

    @Test
    fun `valid agent names`() {
        assertTrue(validAgentName("opencodekz"))
        assertTrue(validAgentName("oc_1"))
        assertTrue(validAgentName("a".repeat(NAME_MAX)))
    }

    @Test
    fun `invalid agent names`() {
        assertFalse(validAgentName(""))
        assertFalse(validAgentName("a"))
        assertFalse(validAgentName("WithCaps"))
        assertFalse(validAgentName("has space"))
        assertFalse(validAgentName("a-b"))
        assertFalse(validAgentName("a".repeat(NAME_TOO_LONG)))
    }

    @Test
    fun `suggested name is always valid`() {
        repeat(NAME_SAMPLES) { assertTrue(validAgentName(suggestedAgentName())) }
    }

    @Test
    fun `registration parses top level key`() {
        val json =
            """
            {"api_key":"moltbook_1","claim_url":"https://www.moltbook.com/claim/moltbook_claim_1",
             "verification_code":"reef-X4B2","status":"pending_claim"}
            """.trimIndent()
        val parsed = parseRegistration(json)
        assertEquals("moltbook_1", parsed.apiKey)
        assertEquals("https://www.moltbook.com/claim/moltbook_claim_1", parsed.claimUrl)
        assertEquals("reef-X4B2", parsed.verificationCode)
        assertEquals("pending_claim", parsed.status)
    }

    @Test
    fun `registration parses nested agent and tweet template`() {
        val json =
            """
            {"agent":{"api_key":"moltbook_2","claim_url":"https://www.moltbook.com/claim/c2",
             "tweet_template":"I am claiming my agent with reef-X4B2"}}
            """.trimIndent()
        val parsed = parseRegistration(json)
        assertEquals("moltbook_2", parsed.apiKey)
        assertEquals("I am claiming my agent with reef-X4B2", parsed.verificationCode)
        assertEquals("pending_claim", parsed.status)
    }

    @Test
    fun `registration without key is refused`() {
        assertThrows(java.io.IOException::class.java) { parseRegistration("""{"claim_url":"https://www.moltbook.com/claim/c"}""") }
        assertThrows(java.io.IOException::class.java) { parseRegistration("""{"api_key":null}""") }
        assertThrows(java.io.IOException::class.java) { parseRegistration("not json") }
    }

    @Test
    fun `status parses both shapes`() {
        assertEquals("claimed", parseAgentStatus("""{"status":"claimed"}"""))
        assertEquals("pending_claim", parseAgentStatus("""{"agent":{"status":"pending_claim"}}"""))
    }

    @Test
    fun `status without usable value is unknown`() {
        assertEquals("unknown", parseAgentStatus("not json"))
        assertEquals("unknown", parseAgentStatus("""{"status":null}"""))
        assertEquals("unknown", parseAgentStatus("{}"))
    }

    @Test
    fun `claim url is restricted to moltbook claim pages`() {
        val good = "https://www.moltbook.com/claim/moltbook_claim_1"
        assertEquals(good, safeClaimUrl(good))
        assertEquals("https://moltbook.com/claim/c2", safeClaimUrl("https://moltbook.com/claim/c2"))
        assertNull(safeClaimUrl("http://www.moltbook.com/claim/c"))
        assertNull(safeClaimUrl("https://evil.example/claim/c"))
        assertNull(safeClaimUrl("https://www.moltbook.com.evil.example/claim/c"))
        assertNull(safeClaimUrl("https://www.moltbook.com/posts/c"))
        assertNull(safeClaimUrl("not a url"))
    }

    @Test
    fun `masked key never shows the whole secret`() {
        val key = "moltbook_supersecretvalue"
        val masked = maskedMoltbookKey(key)
        assertTrue(masked.startsWith("molt"))
        assertTrue(masked.contains("…"))
        assertFalse(masked.contains(key))
        assertTrue(masked.length < key.length)
    }
}
