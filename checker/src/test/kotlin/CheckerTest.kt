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

class TopicSchemeTest {
    @Test
    fun `category is slugged to lowercase`() {
        assertEquals("ai", categorySlug("AI"))
        assertEquals("people", categorySlug("People"))
    }

    @Test
    fun `a change fans out to item, category, and global topics`() {
        assertEquals(
            listOf(
                "iswhateveroutyet-game-fable-game",
                "iswhateveroutyet-game-all",
                "iswhateveroutyet-all",
            ),
            topicsFor("iswhateveroutyet", "Game", "fable-game"),
        )
    }

    @Test
    fun `AI item topic lowercases the category segment`() {
        assertEquals(
            "iswhateveroutyet-ai-claude-fable-5",
            topicsFor("iswhateveroutyet", "AI", "claude-fable-5").first(),
        )
    }
}

class RunDiffTest {
    private val today = LocalDate.of(2026, 6, 28)

    @Test
    fun `an answer flip is a meaningful change`() {
        val prev = listOf(ItemResult("x", "X", "AI", answer = "No."))
        val curr = listOf(ItemResult("x", "X", "AI", answer = "Yes."))
        val changes = diffRuns(prev, today, curr, today)
        assertEquals(1, changes.size)
        assertEquals(true, changes.first().meaningful)
        assertEquals("X: No. → Yes.", changes.first().description)
    }

    @Test
    fun `a ticking gas price is a minor change`() {
        val prev = listOf(ItemResult("gas", "Gas", "Resource", answer = "No?", countdownLabel = "$3.90/gal"))
        val curr = listOf(ItemResult("gas", "Gas", "Resource", answer = "No?", countdownLabel = "$3.95/gal"))
        val changes = diffRuns(prev, today, curr, today)
        assertEquals(1, changes.size)
        assertEquals(false, changes.first().meaningful)
        assertEquals("Gas: $3.90/gal → $3.95/gal", changes.first().description)
    }

    @Test
    fun `a date release that slipped past between runs is meaningful`() {
        val item = ItemResult("gta", "GTA VI", "Game", releaseDate = "2026-11-19")
        // Same stored item both runs; only the reference clock moved across the release date.
        val changes = diffRuns(listOf(item), LocalDate.of(2026, 11, 18), listOf(item), LocalDate.of(2026, 11, 19))
        assertEquals(1, changes.size)
        assertEquals(true, changes.first().meaningful)
        assertEquals("GTA VI: No. → Yes.", changes.first().description)
    }

    @Test
    fun `an identical run produces no changes`() {
        val item = ItemResult("x", "X", "AI", answer = "No.")
        assertEquals(emptyList<RunChange>(), diffRuns(listOf(item), today, listOf(item), today))
    }

    @Test
    fun `a death tone flip is meaningful`() {
        val prev = listOf(ItemResult("c", "Cosby", "People", answer = "Yes."))
        val curr = listOf(ItemResult("c", "Cosby", "People", answer = "Yes.", tone = "death"))
        assertEquals(true, diffRuns(prev, today, curr, today).single().meaningful)
    }
}

class CommitMessageTest {
    @Test
    fun `a single change is the whole subject`() {
        assertEquals("X: No. → Yes.", buildCommitMessage(listOf(RunChange("X: No. → Yes.", true))))
    }

    @Test
    fun `two meaningful changes summarize with a count plus a bullet body`() {
        val msg = buildCommitMessage(listOf(
            RunChange("X: No. → Yes.", true),
            RunChange("Y: No. → Yes.", true),
        ))
        assertEquals("X: No. → Yes. (+1 more)\n\n- X: No. → Yes.\n- Y: No. → Yes.", msg)
    }

    @Test
    fun `a meaningful change headlines over a minor one but the body lists both`() {
        val msg = buildCommitMessage(listOf(
            RunChange("X: No. → Yes.", true),
            RunChange("Gas: $3.90/gal → $3.95/gal", false),
        ))
        assertEquals("X: No. → Yes.\n\n- X: No. → Yes.\n- Gas: $3.90/gal → $3.95/gal", msg)
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
