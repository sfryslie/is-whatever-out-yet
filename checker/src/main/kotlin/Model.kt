import kotlinx.serialization.Serializable
import java.time.LocalDate

// ── Data model ───────────────────────────────────────────────────────────────

/**
 * Either [answer] is set (hardcoded / API check) OR [releaseDate] is set (date-driven, frontend
 * computes Yes/No and countdown from the user's local clock). [vagueLabel] when present means
 * the date is approximate — the frontend shows the fuzzy label and "~N months out" instead of
 * the exact date and days-to-go.
 *
 * [countdownTo] is the *display-only* sibling of [releaseDate]: the frontend shows a countdown
 * to this date but does NOT flip the card on it — used when some other signal (e.g. a Wikipedia
 * check) is the authoritative truth and the date is just "the latest this could happen".
 */
@Serializable
data class ItemResult(
    val id: String,
    val label: String,
    val category: String,
    val answer: String? = null,
    val detail: String? = null,
    val releaseDate: String? = null,
    val vagueLabel: String? = null,
    val countdownTo: String? = null,
    // Server-provided blue subheader (reuses the frontend's countdown block). For date items the
    // frontend computes these client-side instead; here a check can set them directly to surface a
    // live value like the AAA national gas average.
    val countdownLabel: String? = null,
    val countdownSub: String? = null,
    // ISO date (yyyy-MM-dd) for when an already-"out" item became available — powers the frontend's
    // "hide things that have been out a while" filter and the "out for N months" hint. Date-driven
    // items don't need it (their `releaseDate` already encodes when they flip); it's for hardcoded /
    // API / Wikipedia "Yes." items that would otherwise carry no when.
    val since: String? = null,
    // Semantic tone that overrides answer-based card coloring. Currently only "death" — a somber
    // slate instead of celebratory green for "they're out" cards that actually mean someone died.
    val tone: String? = null,
    // Alternative search terms — e.g. ["GTA 6", "GTAVI", "GTA6"] for "Grand Theft Auto VI".
    val aliases: List<String>? = null,
)

// ── Check strategies ─────────────────────────────────────────────────────────

sealed class Check {
    /** Answer is always taken from the item config — no network call. */
    object Hardcoded : Check()

    /**
     * Hardcoded answer/detail plus a `countdownTo` of *next* Jan 1, recomputed every run so it
     * perpetually rolls forward — the card never reaches the date (for "Year of the Linux Desktop").
     */
    object RollingNewYear : Check()

    /** Flips to "Yes." once the wall clock passes the scheduled date. */
    data class ScheduledDate(val date: LocalDate) : Check()

    /**
     * Like ScheduledDate but the public-facing label is fuzzy ("January 2027", "Late 2026?").
     * Underlying date is still used as the flip trigger and to drive the rough countdown.
     */
    data class VagueDate(val date: LocalDate, val vagueLabel: String) : Check()

    /**
     * Hardcoded answer/detail plus a display-only countdown to [date] that never flips the card —
     * for "not out, but here's the event to watch" cases (e.g. a CES reveal date). Emits the same
     * [ItemResult.countdownTo] the frontend renders as a "N days to go" block.
     */
    data class CountdownTo(val date: LocalDate) : Check()

    /** Check the Anthropic /v1/models list for a model whose ID contains [pattern]. */
    data class Anthropic(val pattern: String) : Check()

    /** Check the OpenAI /v1/models list. Skipped gracefully if OPENAI_API_KEY is unset. */
    data class OpenAI(val pattern: String) : Check()

    /** Check the Google Generative Language /v1beta/models list. Skipped gracefully if GOOGLE_API_KEY is unset. */
    data class Gemini(val pattern: String) : Check()

    /** Check the xAI /v1/models list (OpenAI-compatible). Skipped gracefully if XAI_API_KEY is unset. */
    data class Grok(val pattern: String) : Check()

    /** Fetch homestarrunner.com/sitemap.xml and look for sbemail211. */
    object HomestarRunner : Check()

    /**
     * Scrape the AAA gas-prices page at [url] for the national average and surface it as a blue
     * subheader (countdownLabel/Sub). The price also drives the answer: over $6/gal flips to
     * "Yes.", over $5/gal to "Maybe?", otherwise the item defaults hold. Fail-closed: on a network
     * error or parse miss, answer/detail fall back to defaults and the subheader is omitted.
     */
    data class GasPrices(val url: String) : Check()

    /**
     * Fetch the Wikipedia REST summary for [article] and check whether [phrase] still appears in
     * the lead extract. Phrase present → defaultAnswer (condition still holds); phrase missing →
     * "Yes." with the full new extract + a link to the article as the detail. Fail-closed on
     * network errors so transient outages don't flip the card.
     *
     * [latestDate], if set, adds a display-only countdown to that date while the condition still
     * holds — useful for "this could end sooner, but here's the official deadline" cases.
     */
    data class WikipediaLead(
        val article: String,
        val phrase: String,
        val latestDate: LocalDate? = null,
        // Tone to stamp on the result if the phrase disappears (the card flips). Used for the
        // copula-tracking death checks (e.g. Cosby's "is an American" → "was") so the flip colors
        // as a death rather than a celebratory green.
        val flippedTone: String? = null,
    ) : Check()

    /**
     * Like [WikipediaLead] but fetches the full rendered HTML (not just the summary extract),
     * so it can see infobox fields the summary endpoint strips. [phrases] are OR-matched
     * (case-sensitive substring) against the page: while ANY is present the card holds at the
     * item default; only when ALL are gone does it flip. Pass several spellings of the same
     * infobox signal (e.g. "Incarcerated at" / ">Imprisoned<") so a single editor reword doesn't
     * false-flip the card. Keep them capitalized + tag-bounded — infobox values have predictable
     * capitalization that lowercase body prose (which mentions past incarceration forever) does
     * not, so a bare "incarcerated" would freeze the card on "No." Flip → "Maybe?" with
     * [flippedDetail] + Wikipedia link (intentionally not a confident "Yes." — infobox changes
     * can be template churn or transfer notation, not just release).
     *
     * [latestDate], if set, adds a display-only countdown to that date while the condition still
     * holds — e.g. a scheduled parole hearing that isn't itself a release.
     */
    data class WikipediaHtml(
        val article: String,
        val phrases: List<String>,
        val flippedDetail: String,
        val latestDate: LocalDate? = null,
    ) : Check()

    /**
     * Query the AniList GraphQL API for a show's current status and next airing episode.
     * NOT_YET_RELEASED → exact [releaseDate] from AniList if the date is fully confirmed;
     *   otherwise falls back to [vagueDate]/[vagueLabel] until AniList schedules it precisely.
     *   When AniList gives an exact date for a previously-fuzzy item, the transition is a
     *   meaningful change that moves `updated` and fires a push notification.
     * RELEASING / HIATUS → "Yes." + countdownLabel/Sub for next episode if scheduled.
     * FINISHED / CANCELLED → "Yes." with no countdown.
     * No API key required — public data queries are unauthenticated. All fetches are batched
     * into a single GraphQL request. Fails closed on any network/parse error, or on an individual
     * mediaId AniList can't resolve: falls back to the *previous run's* result for that item (not
     * [Item.defaultAnswer]/[vagueDate]) so a transient outage can't undo an already-confirmed
     * "Yes." or exact date — the static defaults/vague fallback only apply on a true first run.
     */
    data class AniList(
        val mediaId: Int,
        val vagueDate: LocalDate? = null,
        val vagueLabel: String? = null,
    ) : Check()

    /**
     * Fetch platform-specific release dates from IGDB for the game at [slug]. The earliest
     * confirmed console/non-PC date drives the card flip; PC (platform 6) is tracked separately
     * and surfaces as detail text (pre-release) or a display-only countdown via
     * [ItemResult.countdownTo] once the primary platform has launched. Requires
     * IGDB_CLIENT_ID + IGDB_CLIENT_SECRET env vars (Twitch app credentials); falls back to the
     * *previous run's* result for this item if the API is unreachable, the slug returns no
     * resolvable date, or credentials are absent — only a true first run (no previous data) falls
     * back to [Item.defaultAnswer]/[Item.defaultDetail].
     */
    data class IGDB(val slug: String) : Check()
}

// ── Item definition ──────────────────────────────────────────────────────────

data class Item(
    val id: String,
    val label: String,
    val category: String,
    val check: Check,
    val defaultAnswer: String = "No.",
    val defaultDetail: String? = null,
    // ISO date the item became "out" — emitted as ItemResult.since for the frontend's hide-old
    // filter. Only meaningful on already-"out" items that aren't date-driven.
    val since: LocalDate? = null,
    // Semantic coloring override; see ItemResult.tone. Set "death" on cards where a green "Yes." is
    // tonally wrong (someone died rather than was released).
    val tone: String? = null,
    // Optional search aliases surfaced in ItemResult for the frontend's filter (e.g. "GTA6", "GTAVI").
    val aliases: List<String>? = null,
)
