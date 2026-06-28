import java.time.LocalDate
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

class StateTrackingTest {
    private val today = LocalDate.of(2026, 6, 28)

    @Test
    fun `date-driven items resolve effective answer against today`() {
        val past = ItemResult("a", "A", "Game", releaseDate = "2025-01-01")
        val future = ItemResult("b", "B", "Game", releaseDate = "2027-01-01")
        assertEquals("Yes.", effectiveAnswer(past, today))
        assertEquals("No.", effectiveAnswer(future, today))
    }

    @Test
    fun `a real state change stamps today`() {
        val prev = ItemResult("x", "X", "AI", answer = "No.")
        val base = ItemResult("x", "X", "AI", answer = "Yes.")
        assertEquals("2026-06-28", resolveSince(prev, base, seed = null, today = today))
    }

    @Test
    fun `tone-only change (a death) also counts as a state change`() {
        val prev = ItemResult("c", "Cosby", "People", answer = "Yes.")
        val base = ItemResult("c", "Cosby", "People", answer = "Yes.", tone = "death")
        assertEquals("2026-06-28", resolveSince(prev, base, seed = "2021-06-30", today = today))
    }

    @Test
    fun `an unchanged item carries its previous since forward`() {
        val prev = ItemResult("d", "D", "Game", answer = "Yes.", since = "2025-09-04")
        val base = ItemResult("d", "D", "Game", answer = "Yes.")
        assertEquals("2025-09-04", resolveSince(prev, base, seed = null, today = today))
    }

    @Test
    fun `a first-seen item trusts the author seed`() {
        val base = ItemResult("e", "E", "Game", answer = "Yes.")
        assertEquals("2024-08-23", resolveSince(prev = null, base = base, seed = "2024-08-23", today = today))
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
