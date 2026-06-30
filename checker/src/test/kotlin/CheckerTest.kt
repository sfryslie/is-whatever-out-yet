import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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

    @Test
    fun `fuzzy to exact date is a meaningful change`() {
        val prev = listOf(ItemResult("a", "Anime", "Show", releaseDate = "2027-01-31", vagueLabel = "January 2027?"))
        val curr = listOf(ItemResult("a", "Anime", "Show", releaseDate = "2027-01-15"))
        val changes = diffRuns(prev, today, curr, today)
        assertEquals(1, changes.size)
        assertEquals(true, changes.first().meaningful)
        assertEquals("Anime: premiere confirmed → 2027-01-15", changes.first().description)
    }

    @Test
    fun `fuzzy date staying fuzzy is a minor change if dates differ`() {
        val prev = listOf(ItemResult("a", "Anime", "Show", releaseDate = "2027-01-31", vagueLabel = "January 2027?"))
        val curr = listOf(ItemResult("a", "Anime", "Show", releaseDate = "2027-01-31", vagueLabel = "January 2027?"))
        assertEquals(emptyList<RunChange>(), diffRuns(prev, today, curr, today))
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

// ── AniList helpers ───────────────────────────────────────────────────────────

class AniListBatchParseTest {
    @Test
    fun `a null media entry is skipped, not treated as a parse failure for the whole batch`() {
        // AniList returns an explicit JSON null (not a missing key) for an ID it can't resolve.
        val body = buildJsonObject {
            putJsonObject("data") {
                put("m1", JsonNull)
                putJsonObject("m2") {
                    put("status", "FINISHED")
                    putJsonObject("startDate") {
                        put("year", 2026); put("month", 1); put("day", 1)
                    }
                }
            }
        }
        val result = parseAniListBatchResponse(body, listOf(1, 2))
        assertEquals(setOf(2), result.keys)
        assertEquals("FINISHED", result.getValue(2).status)
    }

    @Test
    fun `a missing key is skipped same as a null entry`() {
        val body = buildJsonObject {
            putJsonObject("data") {
                putJsonObject("m2") {
                    put("status", "RELEASING")
                }
            }
        }
        val result = parseAniListBatchResponse(body, listOf(1, 2))
        assertEquals(setOf(2), result.keys)
    }

    @Test
    fun `no data key returns an empty map`() {
        assertEquals(emptyMap(), parseAniListBatchResponse(buildJsonObject {}, listOf(1)))
    }

    @Test
    fun `a top-level null data field (GraphQL error response) returns an empty map`() {
        // AniList returns {"data": null, "errors": [...]} on e.g. a rate limit — "data" is an
        // explicit JSON null, not a missing key.
        val body = buildJsonObject { put("data", JsonNull) }
        assertEquals(emptyMap(), parseAniListBatchResponse(body, listOf(1, 2)))
    }
}

// ── IGDB helpers ─────────────────────────────────────────────────────────────

private fun ts(y: Int, m: Int, d: Int): Long =
    LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toEpochSecond()

class IgdbReleaseDateTest {
    @Test
    fun `exact date (category 0) maps to LocalDate via unix timestamp`() {
        val rd = IgdbReleaseDate(167, category = 0, unixDate = ts(2026, 11, 19), year = 2026, month = 11, human = "Nov 19, 2026")
        assertEquals(LocalDate.of(2026, 11, 19), rd.toLocalDate())
    }

    @Test
    fun `month-year (category 1) maps to first of month`() {
        val rd = IgdbReleaseDate(167, category = 1, unixDate = null, year = 2027, month = 2, human = "Feb 2027")
        assertEquals(LocalDate.of(2027, 2, 1), rd.toLocalDate())
    }

    @Test
    fun `year-only (category 2) maps to Dec 31`() {
        val rd = IgdbReleaseDate(null, category = 2, unixDate = null, year = 2028, month = null, human = "2028")
        assertEquals(LocalDate.of(2028, 12, 31), rd.toLocalDate())
    }

    @Test
    fun `Q3 (category 5) maps to Sep 30`() {
        val rd = IgdbReleaseDate(null, category = 5, unixDate = null, year = 2026, month = null, human = "Q3 2026")
        assertEquals(LocalDate.of(2026, 9, 30), rd.toLocalDate())
    }

    @Test
    fun `TBD (category 7) maps to null`() {
        val rd = IgdbReleaseDate(null, category = 7, unixDate = null, year = null, month = null, human = "TBD")
        assertNull(rd.toLocalDate())
    }

    @Test
    fun `exact date has null vague label`() {
        val rd = IgdbReleaseDate(167, category = 0, unixDate = ts(2026, 11, 19), year = 2026, month = 11, human = "Nov 19, 2026")
        assertNull(rd.toVagueLabel())
    }

    @Test
    fun `Q4 year produces correct vague label`() {
        val rd = IgdbReleaseDate(null, category = 6, unixDate = null, year = 2026, month = null, human = "Q4 2026")
        assertEquals("Q4 2026?", rd.toVagueLabel())
    }

    @Test
    fun `month-year produces full month name`() {
        val rd = IgdbReleaseDate(null, category = 1, unixDate = null, year = 2027, month = 9, human = "Sep 2027")
        assertEquals("September 2027?", rd.toVagueLabel())
    }
}

class ParseIgdbReleaseDatesTest {
    @Test
    fun `parses release_dates array into data class list`() {
        val consolets = ts(2026, 11, 19)
        val game = buildJsonObject {
            put("id", 11169)
            put("status", 7)
            put("release_dates", buildJsonArray {
                add(buildJsonObject {
                    put("platform", 167)
                    put("category", 0)
                    put("date", consolets)
                    put("y", 2026)
                    put("m", 11)
                    put("human", "Nov 19, 2026")
                })
                add(buildJsonObject {
                    put("platform", 6)
                    put("category", 7)
                    put("human", "TBD")
                })
            })
        }
        val dates = parseIgdbReleaseDates(game)
        assertEquals(2, dates.size)
        assertEquals(167, dates[0].platformId)
        assertEquals(0, dates[0].category)
        assertEquals(consolets, dates[0].unixDate)
        assertEquals(6, dates[1].platformId)
        assertEquals(7, dates[1].category)
        assertNull(dates[1].unixDate)
    }

    @Test
    fun `category absent but date present infers category 0 (IGDB strips zero fields)`() {
        // IGDB omits category from the response when it's 0 (exact date) — this is the real API shape.
        val consolets = ts(2026, 11, 19)
        val game = buildJsonObject {
            put("release_dates", buildJsonArray {
                add(buildJsonObject {
                    put("platform", 167)
                    // no "category" field — IGDB strips it when value is 0
                    put("date", consolets)
                    put("y", 2026)
                    put("m", 11)
                    put("human", "Nov 19, 2026")
                })
            })
        }
        val dates = parseIgdbReleaseDates(game)
        assertEquals(0, dates[0].category)
        assertEquals(LocalDate.of(2026, 11, 19), dates[0].toLocalDate())
    }

    @Test
    fun `returns empty list when release_dates is absent`() {
        val game = buildJsonObject { put("id", 999) }
        assertEquals(emptyList<IgdbReleaseDate>(), parseIgdbReleaseDates(game))
    }
}

class BuildIgdbResultTest {
    private val today = LocalDate.of(2026, 6, 29)
    private val item  = Item("gta-vi", "Grand Theft Auto VI", "Game", Check.IGDB("grand-theft-auto-vi"),
                            defaultDetail = "Not for PC though, rip.")

    private fun game(status: Int?, vararg dates: IgdbReleaseDate) = buildJsonObject {
        if (status != null) put("status", status)
        put("release_dates", buildJsonArray {
            for (d in dates) add(buildJsonObject {
                if (d.platformId != null) put("platform", d.platformId)
                put("category", d.category)
                if (d.unixDate != null) put("date", d.unixDate)
                if (d.year     != null) put("y", d.year)
                if (d.month    != null) put("m", d.month)
                if (d.human    != null) put("human", d.human)
            })
        })
    }

    private fun consoleExact(date: LocalDate) =
        IgdbReleaseDate(167, 0, ts(date.year, date.monthValue, date.dayOfMonth), date.year, date.monthValue, date.toString())
    private fun pcTbd() = IgdbReleaseDate(IGDB_PC_PLATFORM, 7, null, null, null, "TBD")
    private fun pcExact(date: LocalDate) =
        IgdbReleaseDate(IGDB_PC_PLATFORM, 0, ts(date.year, date.monthValue, date.dayOfMonth), date.year, date.monthValue, date.toString())

    @Test
    fun `future console date — no PC entry — date-driven card with defaultDetail`() {
        val nov19 = LocalDate.of(2026, 11, 19)
        val r = buildIgdbResult(item, game(null, consoleExact(nov19)), today)
        assertEquals("2026-11-19", r.releaseDate)
        assertNull(r.vagueLabel)
        assertEquals("Not for PC though, rip.", r.detail)
        assertNull(r.answer)
    }

    @Test
    fun `future console date — PC TBD — defaultDetail preserved`() {
        val nov19 = LocalDate.of(2026, 11, 19)
        val r = buildIgdbResult(item, game(null, consoleExact(nov19), pcTbd()), today)
        assertEquals("2026-11-19", r.releaseDate)
        assertEquals("Not for PC though, rip.", r.detail)
    }

    @Test
    fun `future console date — confirmed different PC date — PC note in detail`() {
        val nov19 = LocalDate.of(2026, 11, 19)
        val mar15 = LocalDate.of(2027, 3, 15)
        val r = buildIgdbResult(item, game(null, consoleExact(nov19), pcExact(mar15)), today)
        assertEquals("2026-11-19", r.releaseDate)
        assertEquals("PC: $mar15", r.detail)
    }

    @Test
    fun `released (status=0) — no PC — Yes with defaultDetail and since`() {
        val jan1 = LocalDate.of(2026, 1, 1)
        val r = buildIgdbResult(item, game(0, consoleExact(jan1)), today)
        assertEquals("Yes.", r.answer)
        assertEquals("Not for PC though, rip.", r.detail)
        assertEquals("2026-01-01", r.since)
    }

    @Test
    fun `released — PC has future confirmed date — Yes with countdownTo`() {
        val jan1  = LocalDate.of(2026, 1, 1)
        val nov19 = LocalDate.of(2026, 11, 19)
        val r = buildIgdbResult(item, game(0, consoleExact(jan1), pcExact(nov19)), today)
        assertEquals("Yes.", r.answer)
        assertEquals("2026-11-19", r.countdownTo)
        assertEquals("PC release", r.detail)
    }

    @Test
    fun `released — PC also past — Yes with defaultDetail, no countdownTo`() {
        val jan1 = LocalDate.of(2026, 1, 1)
        val r = buildIgdbResult(item, game(0, consoleExact(jan1), pcExact(jan1)), today)
        assertEquals("Yes.", r.answer)
        assertNull(r.countdownTo)
        assertEquals("Not for PC though, rip.", r.detail)
    }

    @Test
    fun `no release date info — falls back to item defaults when there's no previous run`() {
        val g = buildJsonObject { put("id", 999) }
        val r = buildIgdbResult(item, g, today)
        assertEquals("No.", r.answer)
        assertEquals("Not for PC though, rip.", r.detail)
        assertNull(r.releaseDate)
    }

    @Test
    fun `no release date info — falls back to the previous run's confirmed date instead of item defaults`() {
        val g = buildJsonObject { put("id", 999) }
        val prev = ItemResult(item.id, item.label, item.category, releaseDate = "2028-12-31", vagueLabel = "2028?")
        val r = buildIgdbResult(item, g, today, prev)
        assertEquals("2028-12-31", r.releaseDate)
        assertEquals("2028?", r.vagueLabel)
        assertNull(r.answer)
    }

    @Test
    fun `vague Q4 date produces vagueLabel and Dec 31 trigger`() {
        val r = buildIgdbResult(item, game(null, IgdbReleaseDate(167, 6, null, 2026, null, "Q4 2026")), today)
        assertEquals("2026-12-31", r.releaseDate)
        assertEquals("Q4 2026?", r.vagueLabel)
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
