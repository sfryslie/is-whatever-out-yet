import java.time.LocalDate

// ── State tracking (run-to-run diff, `since`, commit message) ─────────────────

/**
 * The card's current effective answer, resolving date-driven items against [today]. The frontend
 * flips those client-side, but for run-to-run change detection the server needs the resolved value.
 */
internal fun effectiveAnswer(r: ItemResult, today: LocalDate): String = when {
    r.answer != null -> r.answer
    r.releaseDate != null -> if (!LocalDate.parse(r.releaseDate).isAfter(today)) "Yes." else "No."
    else -> "?"
}

/**
 * Fingerprint of the meaningful state — answer + tone. `since` resets (and notifications fire) when
 * this changes between runs; detail/countdown churn deliberately doesn't count, so a reworded blurb
 * or a ticking gas price won't reset the clock or spam a ping.
 */
internal fun stateFingerprint(r: ItemResult, today: LocalDate): String =
    effectiveAnswer(r, today) + "|" + (r.tone ?: "")

/**
 * Resolve the `since` (current-state onset) for a freshly computed [base] result by diffing against
 * the [prev] run:
 *  - real state change vs prev → stamp [today]
 *  - unchanged → carry the previous value forward (or adopt a newly-added author [seed])
 *  - first-seen → trust the author [seed]; we can't know a dynamic item's real past flip date.
 * This is what lets, e.g., a long-hidden Cosby card resurface under a tight filter the moment he dies.
 */
internal fun resolveSince(prev: ItemResult?, base: ItemResult, seed: String?, today: LocalDate): String? = when {
    prev == null -> base.since ?: seed
    stateFingerprint(prev, today) != stateFingerprint(base, today) -> today.toString()
    else -> prev.since ?: base.since ?: seed
}

// ── Run-to-run diff (drives `updated` + the commit message) ─────────────────────

/** A described change between two runs. [meaningful] changes move the public `updated` stamp; minor
 *  ones (e.g. the live gas price ticking) only justify a commit so the displayed value stays fresh. */
internal data class RunChange(val description: String, val meaningful: Boolean)

// Displayed fields whose change — absent an answer/tone change — is "minor": a fresh value worth
// committing, but not a real state change. Excludes `since` (state-tied) and the id/label/category
// identity fields.
private fun displayFingerprint(r: ItemResult): String =
    listOf(r.detail, r.releaseDate, r.vagueLabel, r.countdownTo, r.countdownLabel, r.countdownSub)
        .joinToString("|") { it ?: "" }

/**
 * Diff the previous run ([prev], resolved against [prevClock] — the wall clock as of when it was
 * recorded) against [current] (resolved against [today]) and describe what changed. A change is
 * "meaningful" when a card's effective answer or tone flips, or an item is added/removed — that's
 * what moves the public `updated` stamp. Pure display churn (the gas subheader, a reworded blurb)
 * is minor: worth a commit so the page shows the fresh value, but it doesn't move `updated`.
 *
 * Resolving [prev] against its own clock is what catches a date-driven release that slipped past
 * between runs: the stored `releaseDate` never changed, but its effective answer flipped from "No."
 * (as of [prevClock]) to "Yes." (as of [today]).
 */
internal fun diffRuns(
    prev: List<ItemResult>,
    prevClock: LocalDate,
    current: List<ItemResult>,
    today: LocalDate,
): List<RunChange> {
    val prevById = prev.associateBy { it.id }
    val currById = current.associateBy { it.id }
    val changes = mutableListOf<RunChange>()

    prev.filter { it.id !in currById }.forEach {
        changes += RunChange("Removed ${it.label}", meaningful = true)
    }

    current.forEach { cur ->
        val p = prevById[cur.id]
        if (p == null) {
            changes += RunChange("Added ${cur.label}", meaningful = true)
            return@forEach
        }
        val prevAns = effectiveAnswer(p, prevClock)
        val curAns = effectiveAnswer(cur, today)
        when {
            prevAns != curAns ->
                changes += RunChange("${cur.label}: $prevAns → $curAns", meaningful = true)
            cur.tone != p.tone && cur.tone == "death" ->
                changes += RunChange("${cur.label}: $curAns (deceased)", meaningful = true)
            cur.tone != p.tone ->
                changes += RunChange("${cur.label}: tone ${p.tone ?: "none"} → ${cur.tone ?: "none"}", meaningful = true)
            // Fuzzy → exact date: vagueLabel disappears and an exact releaseDate arrives. The card
            // still reads "No." but fans want to know a premiere date just got confirmed.
            p.vagueLabel != null && cur.vagueLabel == null && cur.releaseDate != null ->
                changes += RunChange("${cur.label}: premiere confirmed → ${cur.releaseDate}", meaningful = true)
            displayFingerprint(p) != displayFingerprint(cur) -> {
                val desc = if (cur.countdownLabel != null && cur.countdownLabel != p.countdownLabel)
                    "${cur.label}: ${p.countdownLabel ?: "—"} → ${cur.countdownLabel}"
                else "Updated ${cur.label}"
                changes += RunChange(desc, meaningful = false)
            }
        }
    }
    return changes
}

/** Build a git commit message from a run's [changes]: a one-line summary plus a bullet body. */
internal fun buildCommitMessage(changes: List<RunChange>): String {
    if (changes.isEmpty()) return "chore: update item status"
    val headline = changes.filter { it.meaningful }.ifEmpty { changes }
    val summary = if (headline.size == 1) headline.first().description
                  else "${headline.first().description} (+${headline.size - 1} more)"
    return if (changes.size == 1) summary
           else summary + "\n\n" + changes.joinToString("\n") { "- ${it.description}" }
}

// ── Second pass over a run's results ─────────────────────────────────────────

internal data class StateResolution(val results: List<ItemResult>, val transitions: List<ChangeEvent>)

/**
 * Diff each freshly computed result against the previous run to maintain `since` and collect the
 * "just became out / just died / premiere just confirmed" transitions worth a push notification.
 * First-seen items (no [prevById] entry) never notify, so adding an item or a cold start won't spam.
 */
internal fun trackState(
    items: List<Item>,
    baseResults: List<ItemResult>,
    prevById: Map<String, ItemResult>,
    today: LocalDate,
): StateResolution {
    val seedById = items.associate { it.id to it.since?.toString() }
    val transitions = mutableListOf<ChangeEvent>()
    val results = baseResults.map { base ->
        val prev = prevById[base.id]
        if (prev != null) {
            val becameYes = effectiveAnswer(base, today).startsWith("Yes") &&
                !effectiveAnswer(prev, today).startsWith("Yes")
            val becameDeath = base.tone == "death" && prev.tone != "death"
            // Fuzzy → exact: vagueLabel disappeared and a real releaseDate arrived (still "No." for now,
            // but fans want to know a premiere date just got officially confirmed by the broadcaster).
            val gotExactDate = prev.vagueLabel != null && base.vagueLabel == null && base.releaseDate != null
            if (becameYes || becameDeath || gotExactDate) {
                val message = when {
                    gotExactDate -> "Premiere confirmed: ${base.releaseDate}"
                    becameDeath -> "Looks like they're out."
                    else -> base.detail?.let { stripHtml(it) }?.ifBlank { null } ?: "It's out!"
                }
                transitions += ChangeEvent(base.id, base.label, base.category, message, becameDeath)
            }
        }
        base.copy(since = resolveSince(prev, base, seedById[base.id], today))
    }
    return StateResolution(results, transitions)
}
