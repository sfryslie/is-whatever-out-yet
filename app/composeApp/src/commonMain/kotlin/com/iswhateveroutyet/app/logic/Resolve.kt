package com.iswhateveroutyet.app.logic

import com.iswhateveroutyet.app.model.ItemResult
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.monthsUntil

// Kotlin port of the resolve/filter logic in index.html — keep the two in sync.

/** Card coloring class; mirrors cardClass() in index.html. */
enum class CardTone { YES, NO, SOON, OTHER, GONE }

fun cardTone(item: ItemResult, answer: String): CardTone {
    if (item.tone == "death") return CardTone.GONE
    val a = answer.lowercase()
    return when {
        a.startsWith("yes") -> CardTone.YES
        a.startsWith("no") || a == "never." -> CardTone.NO
        "soon" in a || "next year" in a -> CardTone.SOON
        else -> CardTone.OTHER
    }
}

/** "Hide old stuff" slider stops; `days == null` is Off, `-1` hides anything released. */
data class HideLevel(val label: String, val days: Int?)

val HIDE_LEVELS = listOf(
    HideLevel("Off", null),
    HideLevel("Out over 2 years", 730),
    HideLevel("Out over 1.5 years", 548),
    HideLevel("Out over 1 year", 365),
    HideLevel("Out over 6 months", 183),
    HideLevel("Anything released", -1),
)

private val MONTHS_SHORT =
    listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "Jul 10, 2026" — the site's Intl short-date format. */
fun formatDate(d: LocalDate): String = "${MONTHS_SHORT[d.monthNumber - 1]} ${d.dayOfMonth}, ${d.year}"

fun parseIsoDate(iso: String): LocalDate? = try {
    LocalDate.parse(iso)
} catch (e: Exception) {
    null
}

private fun plural(n: Int, unit: String) = "$n $unit${if (n == 1) "" else "s"}"

/** What actually gets rendered on a card after resolving against the local clock. */
data class Resolved(
    val item: ItemResult,
    val answer: String,
    val detail: String?,
    val countdownLabel: String?,
    val countdownSub: String?,
) {
    val tone: CardTone get() = cardTone(item, answer)
}

/**
 * Resolve a date-driven item against [today]: `releaseDate` drives the Yes/No flip;
 * `countdownTo` is display-only (the server's `answer` stays authoritative). Items with
 * neither pass through untouched, including any server-provided countdownLabel/Sub
 * (e.g. the live gas price).
 */
fun resolveItem(item: ItemResult, today: LocalDate): Resolved {
    val release = item.releaseDate?.let(::parseIsoDate)
    if (release != null) {
        if (today >= release) {
            return Resolved(item, "Yes.", "Released ${formatDate(release)}", null, null)
        }
        val days = today.daysUntil(release)
        return if (item.vagueLabel != null) {
            val months = today.monthsUntil(release)
            val sub = if (months >= 1) "~${plural(months, "month")} out" else "~${plural(days, "day")} out"
            Resolved(item, "No.", item.detail, item.vagueLabel, sub)
        } else {
            Resolved(item, "No.", item.detail, formatDate(release), "${plural(days, "day")} to go")
        }
    }

    val target = item.countdownTo?.let(::parseIsoDate)
    if (target != null && today < target) {
        val days = today.daysUntil(target)
        return Resolved(
            item, item.answer.orEmpty(), item.detail,
            formatDate(target), "${plural(days, "day")} to go",
        )
    }

    return Resolved(item, item.answer.orEmpty(), item.detail, item.countdownLabel, item.countdownSub)
}

/**
 * The date an item became available, or null if it isn't out / we don't know when.
 * `since` is the explicit signal; a past `releaseDate` is the implicit one.
 */
fun outDate(item: ItemResult, today: LocalDate): LocalDate? {
    item.since?.let(::parseIsoDate)?.let { return it }
    item.releaseDate?.let(::parseIsoDate)?.let { if (today >= it) return it }
    return null
}

/** Hidden when the item's out-date is older than the slider level's threshold. */
fun isHiddenByLevel(item: ItemResult, levelIndex: Int, today: LocalDate): Boolean {
    val days = HIDE_LEVELS[levelIndex.coerceIn(HIDE_LEVELS.indices)].days ?: return false
    val out = outDate(item, today) ?: return false
    return out.daysUntil(today) > days
}

/**
 * Soonest-first sort key within a category: an upcoming release/reveal date in epoch days,
 * or MAX_VALUE for items with no future date (kept in catalogue order by stable sort).
 */
fun upcomingSortKey(item: ItemResult, today: LocalDate): Long {
    val d = (item.releaseDate ?: item.countdownTo)?.let(::parseIsoDate) ?: return Long.MAX_VALUE
    return if (d > today) d.toEpochDays().toLong() else Long.MAX_VALUE
}

/** Case-insensitive substring match on label + aliases, same as the site's filter. */
fun matchesSearch(item: ItemResult, filter: String): Boolean {
    if (filter.isEmpty()) return true
    if (item.label.lowercase().contains(filter)) return true
    return item.aliases?.any { it.lowercase().contains(filter) } == true
}
