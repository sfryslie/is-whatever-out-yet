package com.iswhateveroutyet.app

import com.iswhateveroutyet.app.logic.CardTone
import com.iswhateveroutyet.app.logic.cardTone
import com.iswhateveroutyet.app.logic.isHiddenByLevel
import com.iswhateveroutyet.app.logic.resolveItem
import com.iswhateveroutyet.app.logic.upcomingSortKey
import com.iswhateveroutyet.app.model.ItemResult
import com.iswhateveroutyet.app.push.catSlug
import com.iswhateveroutyet.app.push.topicCat
import com.iswhateveroutyet.app.push.topicItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

private fun item(
    id: String = "x",
    category: String = "Video Games",
    answer: String? = null,
    releaseDate: String? = null,
    vagueLabel: String? = null,
    countdownTo: String? = null,
    since: String? = null,
    tone: String? = null,
) = ItemResult(
    id = id, label = id, category = category, answer = answer,
    releaseDate = releaseDate, vagueLabel = vagueLabel, countdownTo = countdownTo,
    since = since, tone = tone,
)

class ResolveTest {
    private val today = LocalDate(2026, 7, 1)

    @Test
    fun scheduledDateFlipsOnRelease() {
        val pre = resolveItem(item(releaseDate = "2026-07-10"), today)
        assertEquals("No.", pre.answer)
        assertEquals("Jul 10, 2026", pre.countdownLabel)
        assertEquals("9 days to go", pre.countdownSub)

        val post = resolveItem(item(releaseDate = "2026-07-01"), today)
        assertEquals("Yes.", post.answer)
        assertEquals("Released Jul 1, 2026", post.detail)
    }

    @Test
    fun vagueDateShowsLabelAndMonths() {
        val r = resolveItem(item(releaseDate = "2027-01-15", vagueLabel = "January 2027?"), today)
        assertEquals("No.", r.answer)
        assertEquals("January 2027?", r.countdownLabel)
        assertEquals("~6 months out", r.countdownSub)
    }

    @Test
    fun countdownToIsDisplayOnly() {
        val r = resolveItem(item(answer = "No.", countdownTo = "2026-08-01"), today)
        assertEquals("No.", r.answer)
        assertEquals("Aug 1, 2026", r.countdownLabel)
        assertEquals("31 days to go", r.countdownSub)
        // Past target: countdown disappears, answer stays authoritative.
        val past = resolveItem(item(answer = "No.", countdownTo = "2026-06-01"), today)
        assertEquals("No.", past.answer)
        assertEquals(null, past.countdownLabel)
    }

    @Test
    fun cardToneMatchesSite() {
        assertEquals(CardTone.YES, cardTone(item(), "Yes."))
        assertEquals(CardTone.NO, cardTone(item(), "No."))
        assertEquals(CardTone.NO, cardTone(item(), "Never."))
        assertEquals(CardTone.SOON, cardTone(item(), "Soon."))
        assertEquals(CardTone.OTHER, cardTone(item(), "Probably."))
        // Death tone overrides even a celebratory Yes.
        assertEquals(CardTone.GONE, cardTone(item(tone = "death"), "Yes."))
    }

    @Test
    fun hideLevels() {
        val oldItem = item(answer = "Yes.", since = "2020-01-01")
        assertFalse(isHiddenByLevel(oldItem, 0, today))          // Off
        assertTrue(isHiddenByLevel(oldItem, 1, today))           // out over 2 years
        val recent = item(answer = "Yes.", since = "2026-06-01")
        assertFalse(isHiddenByLevel(recent, 4, today))           // 30 days < 6 months
        assertTrue(isHiddenByLevel(recent, 5, today))            // anything released
        val notOut = item(answer = "No.")
        assertFalse(isHiddenByLevel(notOut, 5, today))
    }

    @Test
    fun upcomingSortFloatsImminentReleases() {
        val soon = item(releaseDate = "2026-07-05")
        val later = item(releaseDate = "2026-12-01")
        val none = item(answer = "No.")
        val released = item(releaseDate = "2026-01-01")
        assertTrue(upcomingSortKey(soon, today) < upcomingSortKey(later, today))
        assertEquals(Long.MAX_VALUE, upcomingSortKey(none, today))
        assertEquals(Long.MAX_VALUE, upcomingSortKey(released, today))
    }

    @Test
    fun topicsMatchCheckerFormat() {
        assertEquals("video-games", catSlug("Video Games"))
        assertEquals(
            "iswhateveroutyet-video-games-gta6",
            topicItem(item(id = "gta6")),
        )
        assertEquals("iswhateveroutyet-ai-all", topicCat("AI"))
    }
}
