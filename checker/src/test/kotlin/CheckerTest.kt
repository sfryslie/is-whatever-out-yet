import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MatchModelIdTest {
    @Test
    fun `exact id matches`() {
        assertEquals("claude-sonnet-5", matchModelId(listOf("claude-sonnet-5"), "claude-sonnet-5"))
    }

    @Test
    fun `dated suffix matches via prefix`() {
        val ids = listOf("claude-opus-5-20260101", "claude-sonnet-4-6")
        assertEquals("claude-opus-5-20260101", matchModelId(ids, "claude-opus-5"))
    }

    @Test
    fun `preview and experimental variants are excluded`() {
        // Only a preview build is listed — should not count as "out".
        val ids = listOf("gpt-5.6-preview", "gemini-3.1-pro-exp")
        assertNull(matchModelId(ids, "gpt-5.6"))
        assertNull(matchModelId(ids, "gemini-3.1-pro"))
    }

    @Test
    fun `stable build is preferred even when a preview is also present`() {
        val ids = listOf("grok-5-beta", "grok-5")
        assertEquals("grok-5", matchModelId(ids, "grok-5"))
    }

    @Test
    fun `no match returns null`() {
        assertNull(matchModelId(listOf("claude-haiku-4-5"), "claude-opus-5"))
    }
}

class GasAverageRegexTest {
    @Test
    fun `extracts the national average price from AAA-style markup`() {
        val html = """<div>National Average</div><p class="numb"> ${'$'}3.901</p>"""
        val price = GAS_AVG_REGEX.find(html)?.groupValues?.get(1)?.toDoubleOrNull()
        assertEquals(3.901, price)
    }

    @Test
    fun `misses gracefully when the marker is absent`() {
        assertNull(GAS_AVG_REGEX.find("<p>no price here</p>"))
    }
}
