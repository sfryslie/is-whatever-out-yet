import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

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

@Serializable
data class OutputData(
    val updated: String,
    val items: List<ItemResult>,
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

// ── Item catalogue ────────────────────────────────────────────────────────────

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

// Infobox-anchored "still locked up" markers, OR-matched against the full rendered Wikipedia HTML
// by Check.WikipediaHtml: the card stays "No." while ANY is present and flips only when all are
// gone. Capitalized + tag-bounded on purpose so they match infobox value/label cells, not the
// lowercase body prose that mentions past incarceration forever (which would freeze the card).
// Covers the incarcerated↔imprisoned wording editors swap between. Defined before ITEMS so it's
// initialized first.
private val INCARCERATION_MARKERS = listOf("Incarcerated at", ">Incarcerated<", ">Imprisoned<")

// ── IGDB release-date helpers ─────────────────────────────────────────────────

internal const val IGDB_PC_PLATFORM = 6
// PS4=48, Xbox One=49, Switch=130, PS5=167, Xbox Series X|S=169
internal val IGDB_CONSOLE_PLATFORMS = setOf(48, 49, 130, 167, 169)

internal data class IgdbReleaseDate(
    val platformId: Int?,
    val category:   Int,     // 0=exact, 1=month+year, 2=year, 3=Q1…6=Q4, 7=TBD
    val unixDate:   Long?,   // Unix seconds; only set for category 0
    val year:       Int?,
    val month:      Int?,    // 1–12
    val human:      String?, // human-readable label from IGDB, e.g. "Nov 19, 2026"
)

// Maps an IGDB release date to the LocalDate used as our flip trigger / countdown end.
// For vague categories we pick a conservative end-of-period so the card doesn't flip early.
internal fun IgdbReleaseDate.toLocalDate(): LocalDate? = when (category) {
    0    -> unixDate?.let { Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC).toLocalDate() }
    1    -> if (year != null && month != null) LocalDate.of(year, month, 1) else null
    2    -> year?.let { LocalDate.of(it, 12, 31) }
    3    -> year?.let { LocalDate.of(it,  3, 31) }  // Q1
    4    -> year?.let { LocalDate.of(it,  6, 30) }  // Q2
    5    -> year?.let { LocalDate.of(it,  9, 30) }  // Q3
    6    -> year?.let { LocalDate.of(it, 12, 31) }  // Q4
    else -> null                                     // 7 = TBD
}

internal fun IgdbReleaseDate.toVagueLabel(): String? = when (category) {
    0    -> null
    1    -> if (year != null && month != null)
                "${java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)} $year?"
            else null
    2    -> year?.let { "$it?" }
    3    -> year?.let { "Q1 $it?" }
    4    -> year?.let { "Q2 $it?" }
    5    -> year?.let { "Q3 $it?" }
    6    -> year?.let { "Q4 $it?" }
    else -> null
}

internal fun parseIgdbReleaseDates(obj: JsonObject): List<IgdbReleaseDate> =
    obj["release_dates"]?.jsonArray?.map { rd ->
        val o = rd.jsonObject
        IgdbReleaseDate(
            platformId = o["platform"]?.jsonPrimitive?.intOrNull,
            // IGDB omits category=0 (exact date) from responses — infer it from the timestamp.
            category   = o["category"]?.jsonPrimitive?.intOrNull
                         ?: if (o["date"] != null) 0 else 7,
            unixDate   = o["date"]?.jsonPrimitive?.longOrNull,
            year       = o["y"]?.jsonPrimitive?.intOrNull,
            month      = o["m"]?.jsonPrimitive?.intOrNull,
            human      = o["human"]?.jsonPrimitive?.contentOrNull,
        )
    } ?: emptyList()

/**
 * Build an [ItemResult] from a raw IGDB game JSON object ([game]). Primary release date is the
 * earliest confirmed date among console platforms (falling back to all non-PC, then all dates).
 * PC (platform 6) is tracked separately:
 * - Pre-release: if PC has a confirmed date that differs from primary, it appears as detail text.
 *   TBD PC entries are silent so the item's [Item.defaultDetail] (e.g. "Not for PC though, rip.")
 *   can serve as the human-authored fallback.
 * - Post-primary-release: if PC has a future confirmed date, [ItemResult.countdownTo] is set so
 *   the frontend can compute "N days to go" against the user's clock.
 *
 * If IGDB has no resolvable date for this game (empty/TBD-only `release_dates`), falling back to
 * the item's static defaults would silently erase a date a *previous* run already confirmed — so
 * [prev] (the previous run's result for this item, if any) is preferred over the static defaults
 * in that case. `null` (the default) keeps the old defaults-only behavior for callers that don't
 * track run history.
 */
internal fun buildIgdbResult(item: Item, game: JsonObject, today: LocalDate, prev: ItemResult? = null): ItemResult {
    val status   = game["status"]?.jsonPrimitive?.intOrNull  // 0 = Released
    val allDates = parseIgdbReleaseDates(game)

    val pcDate = allDates.firstOrNull { it.platformId == IGDB_PC_PLATFORM }

    // Prefer console dates for primary; fall back to non-PC, then everything.
    val primary = (allDates.filter { it.platformId in IGDB_CONSOLE_PLATFORMS }
        .ifEmpty { allDates.filter { it.platformId != IGDB_PC_PLATFORM } }
        .ifEmpty { allDates })
        .filter { it.category != 7 }
        .minByOrNull { it.toLocalDate() ?: LocalDate.MAX }

    val primaryDate = primary?.toLocalDate()
    val pcLocalDate = pcDate?.toLocalDate()

    val gameReleased = status == 0 || (primaryDate != null && !primaryDate.isAfter(today))
    val pcReleased   = pcLocalDate != null && !pcLocalDate.isAfter(today)

    // "PC: <date>" note — only when IGDB has a confirmed PC date that differs from primary.
    // Omitted for TBD (no resolvable date) so defaultDetail can provide context instead.
    val pcNote = if (pcLocalDate != null && !pcReleased && pcLocalDate != primaryDate)
        pcDate?.human?.let { "PC: $it" } ?: pcDate?.toVagueLabel()?.let { "PC: $it" }
    else null

    return when {
        // All platforms out (or no separate PC tracking needed)
        gameReleased && (pcDate == null || pcReleased) -> ItemResult(
            item.id, item.label, item.category,
            answer = "Yes.",
            detail = item.defaultDetail,
            since  = primaryDate?.toString(),
        )

        // Primary platform is out; PC has a confirmed future date — countdownTo so the
        // frontend computes "N days to go" against the user's local clock.
        gameReleased && pcLocalDate != null -> ItemResult(
            item.id, item.label, item.category,
            answer      = "Yes.",
            countdownTo = pcLocalDate.toString(),
            detail      = "PC release",
        )

        // Primary platform is out; no confirmed PC date
        gameReleased -> ItemResult(
            item.id, item.label, item.category,
            answer = "Yes.",
            detail = item.defaultDetail,
        )

        // Not yet released — date-driven card
        primaryDate != null -> ItemResult(
            item.id, item.label, item.category,
            releaseDate = primaryDate.toString(),
            vagueLabel  = primary?.toVagueLabel(),
            detail      = pcNote ?: item.defaultDetail,
        )

        // No date info — fall back to the previous run's result (a transient/partial IGDB
        // response shouldn't undo a date already confirmed), or the item defaults on first run.
        else -> prev?.copy(id = item.id, label = item.label, category = item.category)
            ?: ItemResult(
                item.id, item.label, item.category,
                answer = item.defaultAnswer,
                detail = item.defaultDetail,
            )
    }
}


val ITEMS = listOf(
    // AI — Anthropic (live API check)
    Item("claude-fable-5",  "Claude Fable 5",  "AI", Check.Anthropic("claude-fable-5")),
    Item("claude-sonnet-5", "Claude Sonnet 5", "AI", Check.Anthropic("claude-sonnet-5")),
    Item("claude-opus-5",   "Claude Opus 5",   "AI", Check.Anthropic("claude-opus-5")),
    Item("claude-haiku-5",  "Claude Haiku 5",  "AI", Check.Anthropic("claude-haiku-5")),
    Item("mythos",          "Claude Mythos 5",   "AI", Check.Anthropic("mythos"), "No.", "Not for us plebs."),

    // AI — other vendors
    Item("gpt-5-6",        "GPT-5.6",         "AI", Check.OpenAI("gpt-5.6"), "No.", "Sol/Terra/Luna Soon™ — I would've thought these were new Pokémon games."),
    Item("gemini-3-1-pro", "Gemini 3.1 Pro",   "AI", Check.Gemini("gemini-3.1-pro")),
    Item("grok-5",         "Grok 5",           "AI", Check.Grok("grok-5"), "No.", "... but do you care?"),
    Item("agi",            "AGI",              "AI", Check.Hardcoded, "No."),

    // Games
    Item("half-life-3",     "Half-Life 3",     "Game", Check.Hardcoded, "No."),
    Item("ricochet-2",      "Ricochet 2",      "Game", Check.Hardcoded, "No."),
    Item("team-fortress-3", "Team Fortress 3", "Game", Check.Hardcoded, "No.",  "<a href=\"https://store.steampowered.com/app/3545060/Team_Fortress_2_Classified/\" target=\"_blank\" rel=\"noopener\">TF2 Classified is kinda fun, though.</a>"),
    Item("left-4-dead-3",   "Left 4 Dead 3",   "Game", Check.Hardcoded, "No."),
    Item("portal-3",        "Portal 3",        "Game", Check.Hardcoded, "No."),
    Item("palworld-1",      "Palworld 1.0",    "Game", Check.ScheduledDate(LocalDate.of(2026, 7, 10))),
    Item("valheim-1",       "Valheim Deep North",     "Game", Check.ScheduledDate(LocalDate.of(2026, 9, 9))),
    Item("deltarune-ch5",   "Deltarune Ch. 5", "Game", Check.Hardcoded, "Yes.",   "Released June 24, 2026.", since = LocalDate.of(2026, 6, 24)),
    Item("deltarune-ch6",   "Deltarune Ch. 6", "Game", Check.Hardcoded, "No.", "Chapter 5 just came out. Relax."),
    Item("persona-6",       "Persona 6",       "Game", Check.Hardcoded, "No."),
    Item("persona-4-revival", "Persona 4 Revival", "Game", Check.IGDB("persona-4-revival")),
    Item("gta-vi",          "Grand Theft Auto VI",      "Game", Check.IGDB("grand-theft-auto-vi"),
        defaultDetail = "Not for PC though, rip.",
        aliases = listOf("GTA 6", "GTA VI", "GTAVI", "GTA6")),
    Item("how-many-dudes",  "How Many Dudes?",          "Game", Check.ScheduledDate(LocalDate.of(2026, 7, 30)),
        defaultDetail = "<a href=\"https://store.steampowered.com/app/3934270/How_Many_Dudes/\" target=\"_blank\" rel=\"noopener\">Demo's out on Steam.</a>"),
    Item("fable-game",      "Fable",                    "Game", Check.IGDB("fable--1")),
    Item("elder-scrolls-6", "The Elder Scrolls VI",     "Game", Check.Hardcoded, "No.", "I'm excited for the Skyrim Remake for the PS6"),
    Item("huniepop-3",      "HuniePop 3",               "Game", Check.Hardcoded, "No.", "I hope it's a roguelite deckbuilder."),
    Item("bge-2",           "Beyond Good and Evil 2",   "Game", Check.Hardcoded, "No.", "Announced 2008. Still waiting."),
    Item("kotor-remake",    "Star Wars: KOTOR Remake",  "Game", Check.Hardcoded, "No.", "Aspyr's teaser baited me into buying a PS5."),
    Item("onimusha-sword",  "Onimusha: Way of the Sword", "Game", Check.IGDB("onimusha-way-of-the-sword")),
    Item("halo-campaign-evolved", "Halo: Campaign Evolved", "Game", Check.IGDB("halo-campaign-evolved"),
        defaultDetail = "Early access July 23."),
    Item("cod-mw4",         "Call of Duty: Modern Warfare 4", "Game", Check.IGDB("call-of-duty-modern-warfare-4"),
        aliases = listOf("COD MW4", "MW4", "Modern Warfare 4")),
    Item("ff7-revelation",  "Final Fantasy VII Revelation", "Game", Check.IGDB("final-fantasy-vii-revelation")),
    Item("bloodborne-2",    "Bloodborne 2",    "Game", Check.Hardcoded, "No."),
    Item("bloodborne-pc",   "Bloodborne: Remastered (PC)", "Game", Check.Hardcoded, "No."),
    Item("elden-ring-2",    "Elden Ring 2",    "Game", Check.Hardcoded, "No."),
    Item("star-citizen",    "Star Citizen 1.0", "Game", Check.Hardcoded, "No."),
    Item("marvels-wolverine", "Marvel's Wolverine", "Game", Check.IGDB("marvels-wolverine")),
    Item("witcher-4",       "The Witcher IV",  "Game", Check.IGDB("the-witcher-iv")),
    Item("truck-kun",       "Truck-kun is Supporting Me From Another World?!", "Game", Check.ScheduledDate(LocalDate.of(2026, 7, 29)),
        defaultDetail = "<a href=\"https://store.steampowered.com/app/3642010/Truckkun_is_Supporting_Me_from_Another_World/\" target=\"_blank\" rel=\"noopener\">It's weeaboo isekai crazy goat taxi simulator I guess?</a>"),
    Item("runescape-dragonwilds", "RuneScape: Dragonwilds", "Game", Check.ScheduledDate(LocalDate.of(2026, 9, 15))),
    Item("gears-of-war-eday", "Gears of War: E-Day", "Game", Check.IGDB("gears-of-war-e-day")),
    Item("enshrouded",      "Enshrouded",      "Game", Check.IGDB("enshrouded")),
    // Already-out games — exercise the "hide long-released" slider at different age buckets.
    Item("silksong",        "Hollow Knight: Silksong", "Game", Check.Hardcoded, "Yes.",
        "Silkposting is a art", since = LocalDate.of(2025, 9, 4)),
    Item("deadlock",        "Deadlock",        "Game", Check.Hardcoded, "No.",
        "<a href=\"https://store.steampowered.com/app/1422450/Deadlock/\" target=\"_blank\" rel=\"noopener\">Still in Early Access.</a>"),
    Item("bloodlines-2",    "Vampire: The Masquerade - Bloodlines 2", "Game", Check.Hardcoded, "Yes.",
        "It's okay. I just like that the devs are The Chinese Room, that's a fun name.", since = LocalDate.of(2025, 10, 21)),

    // Books
    Item("winds-of-winter", "The Winds of Winter",      "Book", Check.Hardcoded, "No."),
    Item("dsm-6",           "DSM-6",                    "Book", Check.Hardcoded, "No.", "<a href=\"https://en.wikipedia.org/wiki/Chatbot_psychosis\" target=\"_blank\" rel=\"noopener\">Chatbot psychosis</a> will likely be in there."),
    Item("doors-of-stone",  "The Doors of Stone",       "Book", Check.Hardcoded, "No."),

    // Shows — AniList-backed (no API key; one batched GraphQL request per run)
    // Premiere-confirmed shows get exact releaseDate; unscheduled ones use vagueDate/Label fallback
    // until AniList locks in a date (that transition fires a push notification).
    Item("rezero-s4-cour2", "Re:Zero Season 4", "Show", Check.AniList(189046),
        aliases = listOf("Re:Zero S4", "Re:Zero S4 Cour 2", "ReZero Season 4", "Re:Zero kara Hajimeru Isekai Seikatsu")),
    Item("youjo-senki-s2", "Saga of Tanya the Evil Season 2", "Show", Check.AniList(135865),
        aliases = listOf("Youjo Senki", "Youjo Senki S2", "Youjo Senki Season 2", "Tanya the Evil", "Tanya the Evil Season 2")),
    Item("mushoku-tensei-s3", "Mushoku Tensei: Jobless Reincarnation Season 3", "Show", Check.AniList(178789),
        aliases = listOf("Mushoku Tensei", "Mushoku Tensei S3", "MT S3", "Jobless Reincarnation Season 3")),
    Item("smoking-supermarket", "Smoking Behind the Supermarket with You", "Show", Check.AniList(196187),
        aliases = listOf("Smoking Supermarket", "Super no Ura de Yani Suu Futari", "Yanisuu")),
    Item("bleach-tybw-calamity", "BLEACH: Thousand-Year Blood War — The Calamity", "Show", Check.AniList(185874),
        aliases = listOf("BLEACH", "Bleach", "Bleach TYBW", "Bleach Thousand-Year Blood War", "Bleach The Calamity")),
    Item("slime-s4", "That Time I Got Reincarnated as a Slime Season 4", "Show", Check.AniList(182205),
        since = LocalDate.of(2026, 4, 3),
        aliases = listOf("TenSura", "Slime S4", "Slime Season 4", "Tensura Season 4", "Rimuru")),
    Item("jjk-s4", "Jujutsu Kaisen: The Culling Game Part 2", "Show",
        Check.AniList(209895, vagueDate = LocalDate.of(2027, 1, 31), vagueLabel = "January 2027?"),
        defaultDetail = "<a href=\"https://www.youtube.com/watch?v=HGCsAcFzaFw\" target=\"_blank\" rel=\"noopener\">Teaser out.</a>",
        aliases = listOf("JJK", "JJK S4", "JJK Season 4", "Jujutsu Kaisen", "Jujutsu Kaisen Season 4", "Culling Game")),
    Item("steel-ball-run-ep2", "Steel Ball Run", "Show",
        Check.AniList(210482, vagueDate = LocalDate.of(2026, 12, 31), vagueLabel = "Late 2026?"),
        defaultDetail = "Fuck Netflix.",
        aliases = listOf("SBR", "JoJo Part 7", "JoJo's Part 7", "JoJo's Bizarre Adventure Part 7", "JoJo's Bizarre Adventure: Steel Ball Run")),
    Item("shangri-la-s3", "Shangri-La Frontier Season 3", "Show",
        Check.AniList(189323, vagueDate = LocalDate.of(2027, 1, 31), vagueLabel = "January 2027"),
        aliases = listOf("SLF S3", "SLF Season 3", "Shangri-La Frontier S3")),
    Item("frieren-s3", "Frieren: Beyond Journey's End Season 3", "Show",
        Check.AniList(209939, vagueDate = LocalDate.of(2027, 10, 31), vagueLabel = "October 2027?"),
        aliases = listOf("Frieren S3", "Frieren Season 3", "Sousou no Frieren", "Sōsō no Furīren")),
    Item("dbs-galactic-patrol", "Dragon Ball Super: The Galactic Patrol", "Show",
        Check.AniList(206812, vagueDate = LocalDate.of(2027, 12, 31), vagueLabel = "Late 2027?"),
        aliases = listOf("DBS", "Dragon Ball Super", "DBS Galactic Patrol")),
    Item("chainsaw-man-s2", "Chainsaw Man: The Assassins Arc", "Show",
        Check.AniList(204429, vagueDate = LocalDate.of(2027, 12, 31), vagueLabel = "Late 2027?"),
        aliases = listOf("Chainsaw Man Season 2", "CSM", "CSM Season 2", "Chainsaw Man S2")),
    Item("cyberpunk-edgerunners-2", "Cyberpunk: Edgerunners 2", "Show",
        Check.AniList(195539, vagueDate = LocalDate.of(2026, 10, 15), vagueLabel = "Fall 2026?"),
        defaultDetail = "<a href=\"https://www.youtube.com/watch?v=mV7451mcw-E\" target=\"_blank\" rel=\"noopener\">Teaser out.</a>",
        aliases = listOf("Edgerunners 2", "Edgerunners Season 2")),
    Item("invincible-s5", "Invincible Season 5", "Show", Check.VagueDate(LocalDate.of(2027, 4, 15), "Spring 2027?")),

    // Movies (date-ordered)
    Item("moana-2026",         "Moana",                  "Movie", Check.ScheduledDate(LocalDate.of(2026, 7, 11)), defaultDetail = "(the live action one)"),
    Item("the-odyssey",        "The Odyssey",            "Movie", Check.ScheduledDate(LocalDate.of(2026, 7, 17))),
    Item("dune-3",              "Dune: Part Three",       "Movie", Check.ScheduledDate(LocalDate.of(2026, 12, 18))),
    Item("avengers-doomsday",   "Avengers: Doomsday",     "Movie", Check.ScheduledDate(LocalDate.of(2026, 12, 18))),
    Item("air-bud-returns",     "Air Bud Returns",        "Movie", Check.ScheduledDate(LocalDate.of(2027, 1, 22))),
    Item("sonic-4",             "Sonic the Hedgehog 4",   "Movie", Check.ScheduledDate(LocalDate.of(2027, 3, 19))),
    Item("spaceballs-new-one",  "Spaceballs: The New One", "Movie", Check.ScheduledDate(LocalDate.of(2027, 4, 23)), defaultDetail = "Sadly not Spaceballs III: The Search for Spaceballs II or Spaceballs 2: The Search for More Money"),
    Item("zelda-movie", "The Legend of Zelda", "Movie", Check.ScheduledDate(LocalDate.of(2027, 4, 30)),
        defaultDetail =
            """
            corpos can't triforce
             ▲
            ▲ ▲
            """.trimIndent() // The space before the top ▲ is a non-breaking space - I think it'll work.
    ),
    Item("starwars-starfighter", "Star Wars: Starfighter", "Movie", Check.ScheduledDate(LocalDate.of(2027, 5, 28)),
        defaultDetail = "It has Ryan Gosling, I guess? Did anyone actually go to the Mandalorian movie?"),
    Item("spiderverse-3",       "Spider-Man: Beyond the Spider-Verse", "Movie", Check.ScheduledDate(LocalDate.of(2027, 6, 18))),
    Item("shrek-5",             "Shrek 5",                "Movie", Check.ScheduledDate(LocalDate.of(2027, 6, 30))),
    Item("quiet-place-3",       "A Quiet Place Part III", "Movie", Check.ScheduledDate(LocalDate.of(2027, 7, 30))),
    Item("demon-slayer-ic-2",   "Demon Slayer: Infinity Castle Part 2", "Movie", Check.VagueDate(LocalDate.of(2027, 9, 22), "Summer 2027?")),
    Item("the-batman-2",        "The Batman Part II",     "Movie", Check.ScheduledDate(LocalDate.of(2027, 10, 1))),
    Item("helldivers",          "Helldivers",             "Movie", Check.ScheduledDate(LocalDate.of(2027, 11, 10))),
    Item("frozen-3",            "Frozen III",             "Movie", Check.ScheduledDate(LocalDate.of(2027, 11, 24))),
    Item("lotr-gollum",         "The Lord of the Rings: The Hunt for Gollum", "Movie", Check.ScheduledDate(LocalDate.of(2027, 12, 17))),
    Item("avengers-secret-wars", "Avengers: Secret Wars", "Movie", Check.ScheduledDate(LocalDate.of(2027, 12, 17))),
    Item("avatar-4",            "Avatar 4: The Tulkun Rider", "Movie", Check.ScheduledDate(LocalDate.of(2029, 12, 21))),

    // People
    Item("diddy",           "Diddy",           "People",
        Check.WikipediaHtml("Sean_Combs", INCARCERATION_MARKERS, flippedDetail = "He's out."),
        defaultDetail = "Serving ~50 months in prison."),
    Item("henry-kissinger", "Henry Kissinger", "People", Check.Hardcoded, "Maybe?", "I think he's still in one of those Myst books?",
        since = LocalDate.of(2023, 11, 29), tone = "death"),
    Item("donald-trump",    "Donald Trump",    "People",
        Check.WikipediaLead("Donald_Trump", "is the 47th president", LocalDate.of(2029, 1, 20)),
        defaultDetail = "Not out of office yet."),
    Item("vladimir-putin",  "Vladimir Putin",  "People",
        Check.WikipediaLead("Vladimir_Putin", "President of Russia since"),
        defaultDetail = "Still President of Russia. Has been since 2012."),
    Item("elizabeth-holmes", "Elizabeth Holmes", "People",
        Check.WikipediaHtml("Elizabeth_Holmes", INCARCERATION_MARKERS, flippedDetail = "She's out."),
        defaultDetail = "Serving 11+ years at FPC Bryan."),
    Item("sbf",              "Sam Bankman-Fried", "People",
        Check.WikipediaHtml("Sam_Bankman-Fried", INCARCERATION_MARKERS, flippedDetail = "He's out."),
        defaultDetail = "25 years at FCI Lompoc I."),
    // Cosby's already out of prison (conviction overturned 2021); the WikipediaLead instead tracks
    // the lead's "is/was an American" copula — when he dies the verb flips and the detail refreshes
    // to the obituary. (The summary endpoint strips the "(born July 12, 1937)" parenthetical, so the
    // birth date itself isn't a usable signal here.)
    Item("bill-cosby",      "Bill Cosby",      "People",
        Check.WikipediaLead("Bill_Cosby", "is an American former comedian", flippedTone = "death"),
        defaultAnswer = "Yes.", defaultDetail = "<a href=\"https://en.wikipedia.org/wiki/Trial_of_Bill_Cosby#Overturned_conviction\" target=\"_blank\" rel=\"noopener\">Released in 2021. It was kinda bullshit.</a>",
        since = LocalDate.of(2021, 6, 30)),
    Item("harvey-weinstein", "Harvey Weinstein", "People",
        Check.WikipediaHtml("Harvey_Weinstein", INCARCERATION_MARKERS, flippedDetail = "He's out."),
        defaultDetail = "Held at Rikers Island."),
    Item("r-kelly",         "R. Kelly",        "People",
        Check.WikipediaHtml("R._Kelly", INCARCERATION_MARKERS, flippedDetail = "He's out."),
        defaultDetail = "Serving 30 years at FCI Butner."),
    Item("jared-fogle",     "Jared Fogle",     "People",
        Check.WikipediaHtml("Jared_Fogle", INCARCERATION_MARKERS, flippedDetail = "He's out."),
        defaultDetail = "~16 years at FCI Englewood. Out around 2029."),
    Item("joe-exotic",      "Joe Exotic",      "People",
        Check.WikipediaHtml("Joe_Exotic", INCARCERATION_MARKERS, flippedDetail = "He's out."),
        defaultDetail = "21 years at FMC Fort Worth. Still no pardon."),
    Item("suge-knight",     "Suge Knight",     "People",
        Check.WikipediaHtml("Suge_Knight", INCARCERATION_MARKERS, flippedDetail = "He's out."),
        defaultDetail = "Serving 28 years at Richard J. Donovan Correctional Facility."),
    Item("danny-masterson", "Danny Masterson", "People",
        Check.WikipediaHtml("Danny_Masterson", INCARCERATION_MARKERS, flippedDetail = "He's out."),
        defaultDetail = "30 years to life at California Men's Colony."),
    Item("ted-kaczynski",   "Ted Kaczynski",   "People", Check.Hardcoded, "Yes.",
        "Yeah, he died in 2023, dude. That was like... a while ago.",
        since = LocalDate.of(2023, 6, 10), tone = "death"),
    Item("oj-simpson",      "O.J. Simpson",    "People", Check.Hardcoded, "No.",
        "The Juice is not loose, he died in 2024.", since = LocalDate.of(2024, 4, 10), tone = "death"),
    Item("sirhan-sirhan",    "Sirhan Sirhan",    "People",
        Check.WikipediaHtml("Sirhan_Sirhan", INCARCERATION_MARKERS, flippedDetail = "He's out.",
            latestDate = LocalDate.of(2027, 8, 16)),
        defaultDetail = "Gavin Newsom overruled the parole board's recommendations. Touchy subject.",
        aliases = listOf("RFK assassin", "Robert F. Kennedy assassin", "Bobby Kennedy shooter")),
    Item("ghislaine-maxwell", "Ghislaine Maxwell", "People",
        Check.WikipediaHtml("Ghislaine_Maxwell", INCARCERATION_MARKERS, flippedDetail = "She's out."),
        defaultDetail = "Such a nasty woman, but Trump wishes her well. Pardon incoming?"),
    Item("the-epstein-list", "The Epstein Client List", "People",
        Check.Hardcoded,
        "No.",
        aliases = listOf("The Epstein files")),
    Item("menendez-brothers", "The Menendez Brothers", "People",
        Check.WikipediaHtml("Lyle_and_Erik_Menendez", INCARCERATION_MARKERS, flippedDetail = "They're out.",
            latestDate = LocalDate.of(2028, 8, 21)),
        defaultDetail = "Both were youth offenders so they're still up for parole.",
        aliases = listOf("Lyle Menendez", "Erik Menendez", "Menendez")),
    Item("john-hinckley-jr", "John Hinckley Jr.", "People", Check.Hardcoded, "Yes.",
        "He has a <a href=\"https://www.youtube.com/channel/UCck3J5KR3INUP1K-hrBe8iA\" target=\"_blank\" rel=\"noopener\">YouTube channel</a> now!",
        since = LocalDate.of(2022, 6, 15),
        aliases = listOf("Reagan shooter", "Ronald Reagan shooter")),
    Item("ed-kemper",        "Ed Kemper",        "People",
        Check.WikipediaHtml("Edmund_Kemper", INCARCERATION_MARKERS, flippedDetail = "He's out.",
            latestDate = LocalDate.of(2031, 7, 9)),
        defaultDetail = "The big 6'9\" mustache guy on <a href=\"https://www.youtube.com/watch?v=cKMMpCuK3bA\" target=\"_blank\" rel=\"noopener\">MINDHUNTER</a>",
        aliases = listOf("Edmund Kemper", "Co-Ed Killer", "Mindhunter")),

    // Resources
    Item("helium",          "Helium",          "Resource", Check.Hardcoded, "No.",  "~200 years of supply remaining. Don't panic."),
    Item("ram",             "RAM",             "Resource", Check.Hardcoded, "Probably.",  "Blame AI."),
    Item("sand",            "Sand",            "Resource", Check.Hardcoded, "Maybe?", "It's actually a major problem, look it up."),
    Item("sulfur",          "Sulfur",          "Resource", Check.Hardcoded, "Maybe?", "A lot of it comes from the Persian Gulf."),
    Item("bananas",         "Bananas",         "Resource", Check.Hardcoded, "Maybe?", "Panama disease for Cavendish bananas in stores."),
    Item("toilet-paper",    "Toilet Paper",    "Resource", Check.Hardcoded, "No.",  "Honestly, just get a <a href=\"https://www.costco.com/p/-/toto-drake-2-piece-elongated-toilet-with-c5-washlet-bidet-seat/4000380465\" target=\"_blank\" rel=\"noopener\">Toto bidet from Costco.</a> Y'know, with like a heated seat and warm water."),
    Item("water",           "Water",           "Resource", Check.Hardcoded, "Maybe?", "Take shorter showers, that water could go to a data center."),
    Item("gas",             "Gas",             "Resource", Check.GasPrices("https://gasprices.aaa.com/"), "No?", "I think we still have reserves."),

    // Tech
    Item("tesla-roadster-2", "Tesla Roadster 2", "Tech",
        Check.WikipediaLead("Tesla_Roadster_(second_generation)", "is an upcoming"),
        defaultDetail = "Announced November 2017. Still upcoming."),
    Item("python-4",        "Python 4",        "Tech", Check.Hardcoded, "No.", "Stop building AI backend services in Python, it sucks. Go use Java/Kotlin and <a href=\"https://spring.io/projects/spring-ai\" target=\"_blank\" rel=\"noopener\">Spring AI</a>."),
    Item("steam-machine",   "Steam Machine",   "Tech", Check.Hardcoded, "Yes.", "<a href=\"https://store.steampowered.com/hardware/steammachine\" target=\"_blank\" rel=\"noopener\">Too expensive though.</a>"),
    Item("steam-frame",     "Steam Frame",     "Tech",
        Check.WikipediaLead("Steam_Frame", "is an upcoming", LocalDate.of(2026, 9, 22)),
        defaultDetail = "Expected Summer 2026."),
    Item("java-valhalla",   "Java Value Types (Valhalla)", "Tech", Check.VagueDate(LocalDate.of(2027, 3, 31), "March 2027?"),
        defaultDetail = "JDK 28 preview."),
    Item("fsd-level-5",     "Level 5 Full Self-Driving", "Tech", Check.Hardcoded, "No."),
    Item("cold-fusion",     "Cold Fusion",     "Tech", Check.Hardcoded, "No."),
    Item("amd-zen-6",       "AMD Zen 6",       "Tech", Check.CountdownTo(LocalDate.of(2027, 1, 6)), "No.", "Might be revealed at CES 2027."),
    Item("rtx-50-super",    "RTX 50 Super Series", "Tech", Check.CountdownTo(LocalDate.of(2027, 1, 6)), "No.", "Might be revealed at CES 2027."),
    Item("rtx-60",          "RTX 60 Series",   "Tech", Check.Hardcoded, "No.", "Blame AI / Jensen."),

    // Internet
    Item("sbemail-211",     "Sbemail 211",     "Internet", Check.HomestarRunner),
    Item("scp-682",         "SCP-682",         "Internet", Check.Hardcoded, "Probably not.", "No need to panic."),
    Item("scp-096",         "SCP-096",         "Internet", Check.Hardcoded, "No.",
        "<a href=\"https://scp-wiki.wikidot.com/incident-096-1-a\" target=\"_blank\" rel=\"noopener\">Four pixels. Four fucking pixels.</a>"),
    Item("year-of-linux",   "Year of the Linux Desktop", "Internet", Check.RollingNewYear, "No."),
)

// ── Match helpers ─────────────────────────────────────────────────────────────

private val PREVIEW_SUFFIXES = listOf("-preview", "-experimental", "-exp", "-beta", "-alpha")
private fun String.isPreviewVariant() = PREVIEW_SUFFIXES.any { contains(it, ignoreCase = true) }

internal fun matchModelId(ids: List<String>, pattern: String): String? =
    ids.firstOrNull { !it.isPreviewVariant() && (it == pattern || it.startsWith("$pattern-") || it.contains(pattern)) }

// ── Network helpers ───────────────────────────────────────────────────────────

suspend fun fetchAnthropicModelIds(client: HttpClient, apiKey: String): List<String> {
    val ids = mutableListOf<String>()
    var url: String? = "https://api.anthropic.com/v1/models?limit=100"
    while (url != null) {
        val body = client.get(url) {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
        }.bodyAsText().let { Json.parseToJsonElement(it).jsonObject }

        body["data"]?.jsonArray?.forEach { m ->
            ids += m.jsonObject["id"]!!.jsonPrimitive.content
        }

        url = if (body["has_more"]?.jsonPrimitive?.boolean == true) {
            body["last_id"]?.jsonPrimitive?.contentOrNull
                ?.let { "https://api.anthropic.com/v1/models?limit=100&after_id=$it" }
        } else null
    }
    return ids
}

suspend fun probeAnthropicModel(client: HttpClient, apiKey: String, modelId: String): Boolean = try {
    val response = client.post("https://api.anthropic.com/v1/messages") {
        header("x-api-key", apiKey)
        header("anthropic-version", "2023-06-01")
        contentType(ContentType.Application.Json)
        setBody("""{"model":"$modelId","max_tokens":1,"messages":[{"role":"user","content":"x"}]}""")
    }
    response.status.value == 200
} catch (e: Exception) {
    false
}

suspend fun fetchOpenAIModelIds(client: HttpClient, apiKey: String): List<String> {
    val body = client.get("https://api.openai.com/v1/models") {
        header("Authorization", "Bearer $apiKey")
    }.bodyAsText().let { Json.parseToJsonElement(it).jsonObject }
    return body["data"]?.jsonArray?.map { it.jsonObject["id"]!!.jsonPrimitive.content } ?: emptyList()
}

suspend fun fetchXaiModelIds(client: HttpClient, apiKey: String): List<String> {
    val body = client.get("https://api.x.ai/v1/models") {
        header("Authorization", "Bearer $apiKey")
    }.bodyAsText().let { Json.parseToJsonElement(it).jsonObject }
    return body["data"]?.jsonArray?.map { it.jsonObject["id"]!!.jsonPrimitive.content } ?: emptyList()
}

suspend fun fetchGeminiModelIds(client: HttpClient, apiKey: String): List<String> {
    val ids = mutableListOf<String>()
    var pageToken: String? = null
    do {
        val url = buildString {
            append("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey&pageSize=100")
            if (pageToken != null) append("&pageToken=$pageToken")
        }
        val body = client.get(url).bodyAsText().let { Json.parseToJsonElement(it).jsonObject }
        body["models"]?.jsonArray?.forEach { m ->
            ids += m.jsonObject["name"]!!.jsonPrimitive.content
        }
        pageToken = body["nextPageToken"]?.jsonPrimitive?.contentOrNull
    } while (pageToken != null)
    return ids
}

suspend fun fetchWikipediaExtract(client: HttpClient, article: String): String? = try {
    val body = client.get("https://en.wikipedia.org/api/rest_v1/page/summary/$article") {
        header("User-Agent", "is-whatever-out-yet (https://iswhateveroutyet.com)")
    }.bodyAsText()
    Json.parseToJsonElement(body).jsonObject["extract"]?.jsonPrimitive?.contentOrNull
} catch (e: Exception) {
    null
}

suspend fun fetchWikipediaHtml(client: HttpClient, article: String): String? = try {
    client.get("https://en.wikipedia.org/api/rest_v1/page/html/$article") {
        header("User-Agent", "is-whatever-out-yet (https://iswhateveroutyet.com)")
    }.bodyAsText()
} catch (e: Exception) {
    null
}

internal data class AniListMedia(
    val status: String,
    val startDate: LocalDate?,
    val nextAiringEpisode: Pair<Long, Int>?,  // airingAt unix timestamp to episode number
)

private val ANILIST_DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy")
// AniList stores airingAt as UTC; startDate fields are already in JST (the broadcast timezone).
// We convert airingAt to JST so the displayed date matches the Japanese broadcast calendar.
private val JST = ZoneOffset.ofHours(9)

/**
 * Parse a raw AniList GraphQL response [body] into per-media results for [mediaIds]. A media entry
 * that's missing or explicitly `null` (AniList returns `null` for an ID it can't resolve — e.g. a
 * merged/deleted entry, or a transient hiccup on just that one ID) is skipped rather than treated
 * as a parse failure: one bad ID shouldn't take the whole batch down with it.
 */
internal fun parseAniListBatchResponse(body: JsonObject, mediaIds: List<Int>): Map<Int, AniListMedia> {
    // `as? JsonObject` (rather than the throwing `.jsonObject` extension) is the load-bearing bit
    // here: AniList legitimately returns a JSON `null` for plenty of fields that are merely absent
    // rather than the key being omitted — "data" itself on a GraphQL error, "startDate" for a show
    // with no confirmed date yet, "nextAiringEpisode" for anything not currently airing. A safe
    // cast treats all of those as "not present" instead of crashing the whole batch on one field.
    val data = body["data"] as? JsonObject ?: return emptyMap()
    return mediaIds.mapNotNull { id ->
        val media = data["m$id"] as? JsonObject ?: return@mapNotNull null
        val status = media["status"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val sd = media["startDate"] as? JsonObject
        val startDate = run {
            val y = sd?.get("year")?.jsonPrimitive?.intOrNull
            val m = sd?.get("month")?.jsonPrimitive?.intOrNull
            val d = sd?.get("day")?.jsonPrimitive?.intOrNull
            if (y != null && m != null && d != null) LocalDate.of(y, m, d) else null
        }
        val nextAiring = (media["nextAiringEpisode"] as? JsonObject)?.let {
            val at = it["airingAt"]?.jsonPrimitive?.longOrNull ?: return@let null
            val ep = it["episode"]?.jsonPrimitive?.intOrNull ?: return@let null
            at to ep
        }
        id to AniListMedia(status, startDate, nextAiring)
    }.toMap()
}

/** Fetch status + next-episode info for all [mediaIds] in a single GraphQL request. */
internal suspend fun fetchAniListBatch(client: HttpClient, mediaIds: List<Int>): Map<Int, AniListMedia> {
    if (mediaIds.isEmpty()) return emptyMap()
    val fields = mediaIds.joinToString(" ") { id ->
        "m$id: Media(id: $id, type: ANIME) { status startDate { year month day } nextAiringEpisode { airingAt episode } }"
    }
    return try {
        val body = client.post("https://graphql.anilist.co") {
            contentType(ContentType.Application.Json)
            setBody("""{"query":"{ $fields }"}""")
        }.bodyAsText().let { Json.parseToJsonElement(it).jsonObject }
        parseAniListBatchResponse(body, mediaIds)
    } catch (e: Exception) {
        println("  AniList batch fetch failed: ${e.message}")
        emptyMap()
    }
}

// Exchange Twitch app credentials for a short-lived bearer token used on all IGDB requests.
// The token lasts ~60 days; for a 30-minute cron it's fine to fetch a fresh one each run.
suspend fun fetchIgdbToken(client: HttpClient, clientId: String, secret: String): String? = try {
    val body = client.post("https://id.twitch.tv/oauth2/token") {
        parameter("client_id", clientId)
        parameter("client_secret", secret)
        parameter("grant_type", "client_credentials")
    }.bodyAsText()
    Json.parseToJsonElement(body).jsonObject["access_token"]?.jsonPrimitive?.contentOrNull
} catch (e: Exception) {
    null
}

suspend fun fetchIgdbGame(client: HttpClient, clientId: String, token: String, slug: String): JsonObject? = try {
    val body = client.post("https://api.igdb.com/v4/games") {
        header("Client-ID", clientId)
        header("Authorization", "Bearer $token")
        contentType(ContentType.Text.Plain)
        setBody(
            "fields name, status, release_dates.category, release_dates.date, " +
            "release_dates.human, release_dates.m, release_dates.platform, release_dates.y; " +
            "where slug = \"$slug\"; limit 1;"
        )
    }.bodyAsText()
    Json.parseToJsonElement(body).jsonArray.firstOrNull()?.jsonObject
} catch (e: Exception) {
    null
}

private fun escapeHtmlText(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

suspend fun checkHomestarRunnerSitemap(client: HttpClient): Pair<String, String> {
    val link210 = "<a href=\"https://homestarrunner.com/sbemails/210-robots\" target=\"_blank\" rel=\"noopener\">Still on 210.</a>"
    return try {
        val xml = client.get("https://homestarrunner.com/sitemap.xml").bodyAsText()
        val locRegex = Regex("<loc>(https://homestarrunner\\.com/sbemails/211[^<]*)</loc>", RegexOption.IGNORE_CASE)
        val match = locRegex.find(xml)
        if (match != null) {
            val url = match.groupValues[1]
            "Yes." to "<a href=\"$url\" target=\"_blank\" rel=\"noopener\">It's here!</a>"
        } else {
            "No." to link210
        }
    } catch (e: Exception) {
        "No." to link210
    }
}

// Pull the national average out of the AAA gas-prices HTML. The value sits in server-rendered
// markup as "National Average …<p class="numb"> $3.901". Returns the raw price (e.g. 3.901) or
// null — the caller formats it for display and compares it against the threshold answers.
internal val GAS_AVG_REGEX = Regex("National Average[\\s\\S]{0,200}?\\\$\\s*([0-9]+\\.[0-9]{2,4})")

suspend fun fetchNationalGasAverage(client: HttpClient, url: String): Double? = try {
    val html = client.get(url) {
        header("User-Agent", "Mozilla/5.0 (compatible; is-whatever-out-yet/1.0; +https://iswhateveroutyet.com)")
    }.bodyAsText()
    GAS_AVG_REGEX.find(html)?.groupValues?.get(1)?.toDoubleOrNull()
} catch (e: Exception) {
    null
}

// ── State tracking + notifications ─────────────────────────────────────────────

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

private fun stripHtml(s: String): String = s.replace(Regex("<[^>]*>"), "").trim()

/** A state change worth notifying about — carries what the topic builder and push payload need. */
internal data class ChangeEvent(
    val id: String,
    val label: String,
    val category: String,
    val message: String,
    val death: Boolean,
)

/**
 * Public topic prefix. MUST match TOPIC_PREFIX in index.html so the frontend's subscribe topics and
 * the checker's push topics line up.
 */
internal const val TOPIC_PREFIX = "iswhateveroutyet"

/** Lowercase, slug-safe form of a category for use in a topic name (e.g. "AI" → "ai"). */
internal fun categorySlug(category: String): String =
    category.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

/**
 * The topics a change fans out to: the specific item, its category firehose, and the global one.
 * Mirrors the topics the frontend subscribes to from each 🔔, so the Web Push Worker can match a
 * change to the right subscribers. There's no wildcard subscribe, hence the explicit rollups.
 */
internal fun topicsFor(prefix: String, category: String, id: String): List<String> {
    val cat = categorySlug(category)
    return listOf("$prefix-$cat-$id", "$prefix-$cat-all", "$prefix-all")
}

/**
 * Hand a change to the Web Push Worker's /send endpoint, which fans it out (encrypted, per RFC 8291)
 * to every browser subscribed to any of [topics]. Authenticated with the shared send token. The
 * Worker owns the VAPID key and subscription store, so the checker just describes what changed.
 * Fail-soft: a delivery error never breaks the run.
 */
internal suspend fun sendPush(client: HttpClient, apiUrl: String, token: String, event: ChangeEvent, topics: List<String>) {
    try {
        val payload = buildJsonObject {
            putJsonArray("topics") { topics.forEach { add(it) } }
            put("title", event.label)
            put("message", event.message)
            put("url", "https://iswhateveroutyet.com/?search=" + java.net.URLEncoder.encode(event.label, "UTF-8"))
            put("tag", event.id)
        }
        val res = client.post(apiUrl.trimEnd('/') + "/send") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        println("  push → ${event.label} (${res.status.value})")
    } catch (e: Exception) {
        println("  push send failed for ${event.label}: ${e.message}")
    }
}

// ── Main ──────────────────────────────────────────────────────────────────────

fun main(): Unit = runBlocking {
    val anthropicKey = System.getenv("ANTHROPIC_API_KEY") ?: error("ANTHROPIC_API_KEY not set")
    val openAiKey    = System.getenv("OPENAI_API_KEY")    // optional
    val googleKey    = System.getenv("GOOGLE_API_KEY")    // optional
    val xaiKey       = System.getenv("XAI_API_KEY")       // optional
    val outputPath   = System.getenv("DATA_JSON_PATH")    ?: "../data.json"
    // Web Push Worker endpoint + shared send token. Both must be set to enable notifications;
    // missing either skips them (e.g. local runs). GitHub Actions sets a `${{ secrets.X }}` env
    // var to an empty string (not an absent var) when the secret isn't configured, so blank counts
    // as unset too — otherwise we'd try to POST to an empty/relative URL and fail noisily instead
    // of skipping cleanly.
    val pushApi      = System.getenv("PUSH_API_URL")?.takeUnless { it.isBlank() }
    val pushToken    = System.getenv("PUSH_SEND_TOKEN")?.takeUnless { it.isBlank() }
    val igdbClientId = System.getenv("IGDB_CLIENT_ID")
    val igdbSecret   = System.getenv("IGDB_CLIENT_SECRET")

    // Load the previous run's full output (items + the `updated` stamp) so we can detect state
    // changes — which drive `since`, ntfy pings, the `updated` timestamp, and the commit message.
    // Missing/unparseable → null, which means "everything is first-seen": no false notifications.
    val prevData: OutputData? = try {
        val f = File(outputPath)
        if (f.exists())
            Json { ignoreUnknownKeys = true }.decodeFromString<OutputData>(f.readText())
        else null
    } catch (e: Exception) {
        println("Could not read previous $outputPath (${e.message}) — treating all items as first-seen.")
        null
    }
    val prevById: Map<String, ItemResult> = prevData?.items?.associateBy { it.id } ?: emptyMap()

    val client = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
    }

    println("Fetching Anthropic models…")
    val anthropicIds = fetchAnthropicModelIds(client, anthropicKey)
    println("  Found ${anthropicIds.size} model(s): ${anthropicIds.joinToString()}")

    val openAiIds: List<String> = if (openAiKey != null) {
        println("Fetching OpenAI models…")
        fetchOpenAIModelIds(client, openAiKey).also {
            println("  Found ${it.size} model(s)")
        }
    } else {
        println("OPENAI_API_KEY not set — skipping OpenAI checks.")
        emptyList()
    }

    val geminiIds: List<String> = if (googleKey != null) {
        println("Fetching Gemini models…")
        fetchGeminiModelIds(client, googleKey).also {
            println("  Found ${it.size} model(s)")
        }
    } else {
        println("GOOGLE_API_KEY not set — skipping Gemini checks.")
        emptyList()
    }

    val xaiIds: List<String> = if (xaiKey != null) {
        println("Fetching xAI models…")
        fetchXaiModelIds(client, xaiKey).also {
            println("  Found ${it.size} model(s)")
        }
    } else {
        println("XAI_API_KEY not set — skipping Grok checks.")
        emptyList()
    }

    val aniListIds = ITEMS.mapNotNull { (it.check as? Check.AniList)?.mediaId }
    val aniListData: Map<Int, AniListMedia> = if (aniListIds.isNotEmpty()) {
        println("Fetching AniList data for ${aniListIds.size} show(s) (single request)…")
        fetchAniListBatch(client, aniListIds).also {
            println("  Got ${it.size}/${aniListIds.size} result(s)")
        }
    } else emptyMap()

    val igdbToken: String? = if (igdbClientId != null && igdbSecret != null) {
        println("Fetching IGDB access token…")
        fetchIgdbToken(client, igdbClientId, igdbSecret).also {
            if (it == null) println("  Failed — IGDB checks will fall back to item defaults.")
            else            println("  Got IGDB token.")
        }
    } else {
        println("IGDB_CLIENT_ID / IGDB_CLIENT_SECRET not set — IGDB checks will use item defaults.")
        null
    }

    val today = LocalDate.now()

    val baseResults = ITEMS.map { item ->
        val result = when (val check = item.check) {
            is Check.Hardcoded -> ItemResult(
                item.id, item.label, item.category, item.defaultAnswer, item.defaultDetail,
                since = item.since?.toString(), tone = item.tone,
            )

            is Check.RollingNewYear -> ItemResult(
                item.id, item.label, item.category,
                answer = item.defaultAnswer,
                detail = item.defaultDetail,
                countdownTo = LocalDate.of(LocalDate.now().year + 1, 1, 1).toString(),
            )

            is Check.ScheduledDate -> ItemResult(
                item.id, item.label, item.category,
                detail = item.defaultDetail,
                releaseDate = check.date.toString(),
            )

            is Check.VagueDate -> ItemResult(
                item.id, item.label, item.category,
                detail = item.defaultDetail,
                releaseDate = check.date.toString(),
                vagueLabel = check.vagueLabel,
            )

            is Check.CountdownTo -> ItemResult(
                item.id, item.label, item.category,
                answer = item.defaultAnswer,
                detail = item.defaultDetail,
                countdownTo = check.date.toString(),
            )

            is Check.Anthropic -> {
                // Listing match excludes preview/experimental variants. If a candidate is found,
                // probe with a 1-token messages call — Anthropic lists models in the catalog
                // before they're actually callable, so we have to verify accessibility.
                val candidate = matchModelId(anthropicIds, check.pattern)
                val callable = candidate != null && probeAnthropicModel(client, anthropicKey, candidate)
                if (candidate != null && !callable) {
                    println("  ${item.label}: listed as '$candidate' but probe failed")
                }
                ItemResult(
                    item.id, item.label, item.category,
                    answer = if (callable) "Yes." else item.defaultAnswer,
                    detail = if (callable) candidate else item.defaultDetail,
                )
            }

            is Check.OpenAI -> {
                if (openAiKey == null) {
                    ItemResult(item.id, item.label, item.category, item.defaultAnswer, item.defaultDetail)
                } else {
                    val matched = matchModelId(openAiIds, check.pattern)
                    ItemResult(
                        item.id, item.label, item.category,
                        answer = if (matched != null) "Yes." else item.defaultAnswer,
                        detail = matched ?: item.defaultDetail,
                    )
                }
            }

            is Check.Gemini -> {
                if (googleKey == null) {
                    ItemResult(item.id, item.label, item.category, item.defaultAnswer, "Add GOOGLE_API_KEY secret to enable live check.")
                } else {
                    val matched = matchModelId(geminiIds, check.pattern)
                    ItemResult(
                        item.id, item.label, item.category,
                        answer = if (matched != null) "Yes." else item.defaultAnswer,
                        detail = matched ?: item.defaultDetail,
                    )
                }
            }

            is Check.Grok -> {
                if (xaiKey == null) {
                    ItemResult(item.id, item.label, item.category, item.defaultAnswer, item.defaultDetail)
                } else {
                    val matched = matchModelId(xaiIds, check.pattern)
                    ItemResult(
                        item.id, item.label, item.category,
                        answer = if (matched != null) "Yes." else item.defaultAnswer,
                        detail = matched ?: item.defaultDetail,
                    )
                }
            }

            is Check.HomestarRunner -> {
                println("Checking Homestar Runner sitemap…")
                val (answer, detail) = checkHomestarRunnerSitemap(client)
                ItemResult(item.id, item.label, item.category, answer, detail)
            }

            is Check.GasPrices -> {
                println("Checking AAA national gas average…")
                val price = fetchNationalGasAverage(client, check.url)
                val label = price?.let { "$" + "%.2f".format(it) + "/gal" }
                // The price drives the answer: over $6/gal it's "out," over $5/gal it's getting
                // there. Below that (or on a parse/network miss) the item defaults hold.
                val (answer, detail) = when {
                    price == null -> item.defaultAnswer to item.defaultDetail
                    price > 6.0   -> "Yes." to "Over six bucks a gallon. Yeah, it's a problem."
                    price > 5.0   -> "Maybe?" to "Five-plus a gallon and climbing."
                    else          -> item.defaultAnswer to item.defaultDetail
                }
                ItemResult(
                    item.id, item.label, item.category,
                    answer = answer,
                    detail = detail,
                    countdownLabel = label,
                    countdownSub = if (label != null) "U.S. average · AAA" else null,
                )
            }

            is Check.IGDB -> {
                // Fail closed: missing credentials or an unreachable/empty IGDB response shouldn't
                // overwrite a date a previous run already confirmed — prefer that over the item's
                // static defaults, and only fall back to the defaults on a true first run.
                val staleOrDefault = {
                    prevById[item.id]?.copy(id = item.id, label = item.label, category = item.category)
                        ?: ItemResult(item.id, item.label, item.category, item.defaultAnswer, item.defaultDetail)
                }
                if (igdbClientId == null || igdbToken == null) {
                    staleOrDefault()
                } else {
                    println("Checking IGDB for ${item.label} (slug: ${check.slug})…")
                    val game = fetchIgdbGame(client, igdbClientId, igdbToken, check.slug)
                    if (game == null) {
                        println("  IGDB: no result for '${check.slug}' — using previous run's data")
                        staleOrDefault()
                    } else {
                        buildIgdbResult(item, game, today, prevById[item.id])
                    }
                }
            }

            is Check.WikipediaLead -> {
                println("Checking Wikipedia lead for ${check.article}…")
                val extract = fetchWikipediaExtract(client, check.article)
                if (extract == null || extract.contains(check.phrase, ignoreCase = true)) {
                    ItemResult(
                        item.id, item.label, item.category,
                        answer = item.defaultAnswer,
                        detail = item.defaultDetail,
                        countdownTo = check.latestDate?.toString(),
                        since = item.since?.toString(),
                    )
                } else {
                    // Early flip — Wikipedia signal beat the deadline. Drop the countdown.
                    val articleUrl = "https://en.wikipedia.org/wiki/${check.article}"
                    ItemResult(
                        item.id, item.label, item.category,
                        answer = "Yes.",
                        detail = "${escapeHtmlText(extract)} <a href=\"$articleUrl\" target=\"_blank\" rel=\"noopener\">(Wikipedia)</a>",
                        tone = check.flippedTone,
                    )
                }
            }

            is Check.WikipediaHtml -> {
                println("Checking Wikipedia HTML for ${check.article}…")
                val html = fetchWikipediaHtml(client, check.article)
                if (html == null || check.phrases.any { html.contains(it) }) {
                    ItemResult(
                        item.id, item.label, item.category,
                        answer = item.defaultAnswer,
                        detail = item.defaultDetail,
                        countdownTo = check.latestDate?.toString(),
                    )
                } else {
                    // Early flip — Wikipedia signal beat the deadline. Drop the countdown.
                    val articleUrl = "https://en.wikipedia.org/wiki/${check.article}"
                    ItemResult(
                        item.id, item.label, item.category,
                        answer = "Maybe?",
                        detail = "${check.flippedDetail} <a href=\"$articleUrl\" target=\"_blank\" rel=\"noopener\">(Wikipedia)</a>",
                    )
                }
            }

            is Check.AniList -> {
                val media = aniListData[check.mediaId]
                if (media == null) {
                    // Fail closed: network error, or this mediaId missing/null in the batch result.
                    // Falling back to the item's static default would silently undo a previously
                    // confirmed "Yes." or exact date — prefer the previous run's result instead,
                    // and only use the vague placeholder on a true first run.
                    prevById[item.id]?.copy(id = item.id, label = item.label, category = item.category)
                        ?: ItemResult(item.id, item.label, item.category, item.defaultAnswer, item.defaultDetail,
                            releaseDate = check.vagueDate?.toString(), vagueLabel = check.vagueLabel)
                } else when (media.status) {
                    "NOT_YET_RELEASED" -> {
                        // Prefer the fully-specified startDate (already in JST, the broadcast calendar).
                        // If startDate is partial/missing, try converting nextAiringEpisode to JST.
                        val exactDate = media.startDate ?: media.nextAiringEpisode?.let { (airingAt, _) ->
                            Instant.ofEpochSecond(airingAt).atZone(JST).toLocalDate()
                        }
                        if (exactDate != null) {
                            ItemResult(item.id, item.label, item.category, detail = item.defaultDetail,
                                releaseDate = exactDate.toString())
                        } else {
                            // No confirmed date yet — hold at the vague fallback
                            ItemResult(item.id, item.label, item.category, detail = item.defaultDetail,
                                releaseDate = check.vagueDate?.toString(), vagueLabel = check.vagueLabel)
                        }
                    }
                    "RELEASING", "HIATUS" -> {
                        val (countdownLabel, countdownSub) = media.nextAiringEpisode?.let { (airingAt, episode) ->
                            val date = Instant.ofEpochSecond(airingAt).atZone(JST).toLocalDate()
                            "Ep. $episode airs" to ANILIST_DATE_FMT.format(date)
                        } ?: (null to null)
                        ItemResult(item.id, item.label, item.category, answer = "Yes.",
                            detail = item.defaultDetail, countdownLabel = countdownLabel, countdownSub = countdownSub)
                    }
                    else -> // FINISHED, CANCELLED
                        ItemResult(item.id, item.label, item.category, answer = "Yes.", detail = item.defaultDetail)
                }
            }
        }
        result.copy(aliases = item.aliases)
    }

    // Second pass: diff each result against the previous run to maintain `since` and collect the
    // "just became out / just died" transitions worth a notification.
    val seedById = ITEMS.associate { it.id to it.since?.toString() }
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

    if (pushApi == null || pushToken == null) {
        println("PUSH_API_URL / PUSH_SEND_TOKEN not set — skipping notifications.")
    } else if (transitions.isNotEmpty()) {
        println("Sending push for ${transitions.size} change(s)…")
        transitions.take(8).forEach { ev ->
            // The Worker fans each change out to the item, category, and global topics' subscribers.
            sendPush(client, pushApi, pushToken, ev, topicsFor(TOPIC_PREFIX, ev.category, ev.id))
        }
    }

    client.close()

    // Decide what (if anything) actually changed this run. `updated` only moves on a *meaningful*
    // change (a card's answer/tone, or an added/removed item) — not on pure display churn like the
    // live gas price ticking — so the public timestamp reflects the last real change, not the last
    // poll. The commit message handed to the workflow names what changed; when nothing changed at
    // all the file is byte-identical to last run and the workflow skips the commit entirely.
    val prevClock = prevData?.updated?.let {
        try { Instant.parse(it).atZone(ZoneOffset.UTC).toLocalDate() } catch (e: Exception) { today }
    } ?: today
    val changes = if (prevData == null) listOf(RunChange("Initialize tracked data", meaningful = true))
                  else diffRuns(prevData.items, prevClock, results, today)
    val meaningful = changes.any { it.meaningful }
    val updated = if (prevData != null && !meaningful) prevData.updated else Instant.now().toString()

    val output = OutputData(updated = updated, items = results)
    val json = Json { prettyPrint = true }
    File(outputPath).writeText(json.encodeToString(output))

    // Hand the workflow a commit message naming the change(s). Only written when something changed;
    // otherwise the file is unchanged and there's nothing to commit.
    System.getenv("COMMIT_MSG_PATH")?.let { msgPath ->
        if (changes.isNotEmpty()) File(msgPath).writeText(buildCommitMessage(changes))
    }

    if (changes.isEmpty()) {
        println("\nNo changes since last run — $outputPath is identical, nothing to commit.")
    } else {
        println("\nChanges this run (updated ${if (meaningful) "→ $updated" else "unchanged: $updated"}):")
        changes.forEach { println("  - ${it.description}${if (it.meaningful) "" else " (minor)"}") }
    }

    println("\nWrote $outputPath:")
    results.forEach { r ->
        val note = r.detail?.let { " ($it)" } ?: ""
        println("  [${r.category}] ${r.label}: ${r.answer}$note")
    }

    exitProcess(0)
}
