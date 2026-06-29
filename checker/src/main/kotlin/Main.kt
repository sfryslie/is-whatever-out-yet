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
     */
    data class WikipediaHtml(
        val article: String,
        val phrases: List<String>,
        val flippedDetail: String,
    ) : Check()
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
)

// Infobox-anchored "still locked up" markers, OR-matched against the full rendered Wikipedia HTML
// by Check.WikipediaHtml: the card stays "No." while ANY is present and flips only when all are
// gone. Capitalized + tag-bounded on purpose so they match infobox value/label cells, not the
// lowercase body prose that mentions past incarceration forever (which would freeze the card).
// Covers the incarcerated↔imprisoned wording editors swap between. Defined before ITEMS so it's
// initialized first.
private val INCARCERATION_MARKERS = listOf("Incarcerated at", ">Incarcerated<", ">Imprisoned<")

val ITEMS = listOf(
    // AI — Anthropic (live API check)
    Item("claude-fable-5",  "Claude Fable 5",  "AI", Check.Anthropic("claude-fable-5")),
    Item("claude-sonnet-5", "Claude Sonnet 5", "AI", Check.Anthropic("claude-sonnet-5")),
    Item("claude-opus-5",   "Claude Opus 5",   "AI", Check.Anthropic("claude-opus-5")),
    Item("claude-haiku-5",  "Claude Haiku 5",  "AI", Check.Anthropic("claude-haiku-5")),
    Item("claude-fable-6",  "Claude Fable 6",  "AI", Check.Anthropic("claude-fable-6")),
    Item("mythos",          "Claude Mythos",     "AI", Check.Anthropic("mythos"), "No.", "Probably not for you."),

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
    Item("persona-4-revival", "Persona 4 Revival", "Game", Check.ScheduledDate(LocalDate.of(2027, 2, 18))),
    Item("gta-vi",          "Grand Theft Auto VI",      "Game", Check.ScheduledDate(LocalDate.of(2026, 11, 19))),
    Item("gta-vi-pc",       "Grand Theft Auto VI (PC)", "Game", Check.Hardcoded, "No.", "Rip"),
    Item("how-many-dudes",  "How Many Dudes?",          "Game", Check.ScheduledDate(LocalDate.of(2026, 7, 30)),
        defaultDetail = "<a href=\"https://store.steampowered.com/app/3934270/How_Many_Dudes/\" target=\"_blank\" rel=\"noopener\">Demo's out on Steam.</a>"),
    Item("fable-game",      "Fable",                    "Game", Check.ScheduledDate(LocalDate.of(2027, 2, 23))),
    Item("elder-scrolls-6", "The Elder Scrolls VI",     "Game", Check.Hardcoded, "No.", "Bethesda's been in development since 2018. Buy Skyrim again."),
    Item("huniepop-3",      "HuniePop 3",               "Game", Check.Hardcoded, "No.", "I hope it's a roguelite deckbuilder."),
    Item("bge-2",           "Beyond Good and Evil 2",   "Game", Check.Hardcoded, "No.", "Announced 2008. Still waiting."),
    Item("kotor-remake",    "Star Wars: KOTOR Remake",  "Game", Check.Hardcoded, "No.", "In development limbo since 2021."),
    Item("onimusha-sword",  "Onimusha: Way of the Sword", "Game", Check.ScheduledDate(LocalDate.of(2026, 9, 25))),
    Item("halo-campaign-evolved", "Halo: Campaign Evolved", "Game", Check.ScheduledDate(LocalDate.of(2026, 7, 28)),
        defaultDetail = "Early access July 23."),
    Item("cod-mw4",         "Call of Duty: MW4",        "Game", Check.ScheduledDate(LocalDate.of(2026, 10, 23))),
    Item("ff7-revelation",  "Final Fantasy VII Revelation", "Game", Check.VagueDate(LocalDate.of(2027, 6, 20), "Spring 2027?")),
    Item("bloodborne-2",    "Bloodborne 2",    "Game", Check.Hardcoded, "No."),
    Item("bloodborne-pc",   "Bloodborne: Remastered (PC)", "Game", Check.Hardcoded, "No."),
    Item("elden-ring-2",    "Elden Ring 2",    "Game", Check.Hardcoded, "No."),
    Item("star-citizen",    "Star Citizen 1.0", "Game", Check.Hardcoded, "No."),
    Item("marvels-wolverine", "Marvel's Wolverine", "Game", Check.ScheduledDate(LocalDate.of(2026, 9, 15))),
    Item("witcher-4",       "The Witcher IV",  "Game", Check.VagueDate(LocalDate.of(2028, 12, 31), "2028?")),
    // Already-out games — exercise the "hide long-released" slider at different age buckets.
    Item("silksong",        "Hollow Knight: Silksong", "Game", Check.Hardcoded, "Yes.",
        "Released September 4, 2025. Worth the wait.", since = LocalDate.of(2025, 9, 4)),
    Item("deadlock",        "Deadlock",        "Game", Check.Hardcoded, "No.",
        "<a href=\"https://store.steampowered.com/app/1422450/Deadlock/\" target=\"_blank\" rel=\"noopener\">Still in Early Access.</a>"),
    Item("bloodlines-2",    "Vampire: The Masquerade - Bloodlines 2", "Game", Check.Hardcoded, "Yes.",
        "Finally. Announced 2019, out 2025.", since = LocalDate.of(2025, 10, 21)),

    // Books
    Item("winds-of-winter", "The Winds of Winter",      "Book", Check.Hardcoded, "No.", "GRRM started writing it in 2010. Watch the show again."),
    Item("dsm-6",           "DSM-6",                    "Book", Check.Hardcoded, "No.", "AI <a href=\"https://en.wikipedia.org/wiki/Chatbot_psychosis\" target=\"_blank\" rel=\"noopener\">chatbot psychosis</a> will likely be in there."),
    Item("doors-of-stone",  "The Doors of Stone",       "Book", Check.Hardcoded, "No."),

    // Shows
    Item("rezero-s4-cour2",     "Re:Zero S4 Cour 2",      "Show", Check.ScheduledDate(LocalDate.of(2026, 8, 12))),
    Item("jjk-s4",              "Jujutsu Kaisen S4",      "Show", Check.VagueDate(LocalDate.of(2027, 1, 31), "January 2027?")),
    Item("steel-ball-run-ep2",  "Steel Ball Run Ep. 2",   "Show", Check.VagueDate(LocalDate.of(2026, 12, 31), "Late 2026?"),
        defaultDetail = "Fuck Netflix."),
    Item("shangri-la-s3",       "Shangri-La Frontier S3", "Show", Check.VagueDate(LocalDate.of(2027, 1, 31), "January 2027")),
    Item("frieren-s3",          "Frieren S3",             "Show", Check.VagueDate(LocalDate.of(2027, 10, 31), "October 2027?")),
    Item("dbs-galactic-patrol", "Dragon Ball Super: The Galactic Patrol", "Show", Check.VagueDate(LocalDate.of(2027, 12, 31), "Late 2027?")),
    Item("chainsaw-man-s2",     "Chainsaw Man Season 2",  "Show", Check.VagueDate(LocalDate.of(2027, 12, 31), "Late 2027?")),

    // Movies (date-ordered)
    Item("dune-3",              "Dune: Part Three",       "Movie", Check.ScheduledDate(LocalDate.of(2026, 12, 18))),
    Item("avengers-doomsday",   "Avengers: Doomsday",     "Movie", Check.ScheduledDate(LocalDate.of(2026, 12, 18))),
    Item("air-bud-returns",     "Air Bud Returns",        "Movie", Check.ScheduledDate(LocalDate.of(2027, 1, 22))),
    Item("sonic-4",             "Sonic the Hedgehog 4",   "Movie", Check.ScheduledDate(LocalDate.of(2027, 3, 19))),
    Item("spaceballs-new-one",  "Spaceballs: The New One", "Movie", Check.ScheduledDate(LocalDate.of(2027, 4, 23))),
    Item("zelda-movie",         "The Legend of Zelda",    "Movie", Check.ScheduledDate(LocalDate.of(2027, 4, 30)),
        defaultDetail = "I'm morbidly curious."),
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
        defaultDetail = "Still the 47th president."),
    Item("vladimir-putin",  "Vladimir Putin",  "People",
        Check.WikipediaLead("Vladimir_Putin", "President of Russia since"),
        defaultDetail = "Still President of Russia. Has been since 2012."),
    Item("elizabeth-holmes", "Elizabeth Holmes", "People",
        Check.WikipediaHtml("Elizabeth_Holmes", INCARCERATION_MARKERS, flippedDetail = "She's out."),
        defaultDetail = "Serving 11+ years at FPC Bryan."),
    Item("sbf",              "Sam Bankman-Fried", "People",
        Check.WikipediaHtml("Sam_Bankman-Fried", INCARCERATION_MARKERS, flippedDetail = "He's out."),
        defaultDetail = "25 years at FCI Lompoc I. Don't hold your breath."),
    // Cosby's already out of prison (conviction overturned 2021); the WikipediaLead instead tracks
    // the lead's "is/was an American" copula — when he dies the verb flips and the detail refreshes
    // to the obituary. (The summary endpoint strips the "(born July 12, 1937)" parenthetical, so the
    // birth date itself isn't a usable signal here.)
    Item("bill-cosby",      "Bill Cosby",      "People",
        Check.WikipediaLead("Bill_Cosby", "is an American former comedian", flippedTone = "death"),
        defaultAnswer = "Yes.", defaultDetail = "Released in 2021. <a href=\"https://en.wikipedia.org/wiki/Trial_of_Bill_Cosby#Overturned_conviction\" target=\"_blank\" rel=\"noopener\">It was kinda bullshit.</a>",
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
    Item("ted-kaczynski",   "Ted Kaczynski",   "People", Check.Hardcoded, "Yes.",
        "Yeah, he died in 2023, dude. That was like... a while ago.",
        since = LocalDate.of(2023, 6, 10), tone = "death"),
    Item("oj-simpson",      "O.J. Simpson",    "People", Check.Hardcoded, "No.",
        "The Juice is not loose, he died in 2024.", since = LocalDate.of(2024, 4, 10), tone = "death"),

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
    Item("amd-zen-6",       "AMD Zen 6",       "Tech", Check.CountdownTo(LocalDate.of(2027, 1, 6)), "No.", "Might be revealed then."),
    Item("rtx-50-super",    "RTX 50 Super Series", "Tech", Check.CountdownTo(LocalDate.of(2027, 1, 6)), "No.", "Might be revealed then."),
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

private fun stripHtml(s: String): String = s.replace(Regex("<[^>]*>"), "").trim()

/** A state change worth notifying about — carries what the topic builder and message need. */
internal data class NtfyEvent(
    val id: String,
    val label: String,
    val category: String,
    val message: String,
    val death: Boolean,
)

/** Lowercase, slug-safe form of a category for use in a topic name (e.g. "AI" → "ai"). */
internal fun categorySlug(category: String): String =
    category.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

/**
 * The ntfy topics a change should fan out to, given the public [prefix]: the specific item, its
 * category firehose, and the global firehose. Mirrors the link scheme the frontend builds, so a
 * card's 🔔 and the checker's POST target the same topic. ntfy has no wildcard subscribe, hence
 * the explicit `-<category>-all` and `-all` rollups.
 */
internal fun ntfyTopicsFor(prefix: String, category: String, id: String): List<String> {
    val cat = categorySlug(category)
    return listOf("$prefix-$cat-$id", "$prefix-$cat-all", "$prefix-all")
}

/**
 * Publish a single notification to an ntfy topic (https://ntfy.sh/<topic> by default). Plain HTTP
 * POST — ntfy handles fan-out to anyone subscribed to the topic, so there's no subscription store to
 * maintain. Fail-soft: a delivery error never breaks the run.
 */
suspend fun sendNtfy(client: HttpClient, server: String, topic: String, label: String, message: String, death: Boolean) {
    try {
        client.post("$server/$topic") {
            header("Title", label)
            header("Tags", if (death) "skull" else "tada")
            header("Click", "https://iswhateveroutyet.com/?search=" + java.net.URLEncoder.encode(label, "UTF-8"))
            setBody(message)
        }
        println("  ntfy → $label")
    } catch (e: Exception) {
        println("  ntfy send failed for $label: ${e.message}")
    }
}

// ── Main ──────────────────────────────────────────────────────────────────────

fun main(): Unit = runBlocking {
    val anthropicKey = System.getenv("ANTHROPIC_API_KEY") ?: error("ANTHROPIC_API_KEY not set")
    val openAiKey    = System.getenv("OPENAI_API_KEY")    // optional
    val googleKey    = System.getenv("GOOGLE_API_KEY")    // optional
    val xaiKey       = System.getenv("XAI_API_KEY")       // optional
    val outputPath   = System.getenv("DATA_JSON_PATH")    ?: "../data.json"
    // Public topic prefix (e.g. "iswhateveroutyet"); also the on-switch — notifications are skipped
    // if unset. Must match NTFY_PREFIX in index.html so the frontend's 🔔 links and the checker's
    // pushes target the same topics.
    val ntfyPrefix   = System.getenv("NTFY_TOPIC_PREFIX")
    val ntfyServer   = System.getenv("NTFY_SERVER") ?: "https://ntfy.sh"

    // Load the previous run's results so we can detect state changes (drives `since` and ntfy pings).
    // Missing/unparseable → empty, which means "everything is first-seen": no false notifications.
    val prevById: Map<String, ItemResult> = try {
        val f = File(outputPath)
        if (f.exists())
            Json { ignoreUnknownKeys = true }.decodeFromString<OutputData>(f.readText()).items.associateBy { it.id }
        else emptyMap()
    } catch (e: Exception) {
        println("Could not read previous $outputPath (${e.message}) — treating all items as first-seen.")
        emptyMap()
    }

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

    val baseResults = ITEMS.map { item ->
        when (val check = item.check) {
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
                    ItemResult(item.id, item.label, item.category, item.defaultAnswer, item.defaultDetail)
                } else {
                    val articleUrl = "https://en.wikipedia.org/wiki/${check.article}"
                    ItemResult(
                        item.id, item.label, item.category,
                        answer = "Maybe?",
                        detail = "${check.flippedDetail} <a href=\"$articleUrl\" target=\"_blank\" rel=\"noopener\">(Wikipedia)</a>",
                    )
                }
            }
        }
    }

    // Second pass: diff each result against the previous run to maintain `since` and collect the
    // "just became out / just died" transitions worth a notification.
    val today = LocalDate.now()
    val seedById = ITEMS.associate { it.id to it.since?.toString() }
    val transitions = mutableListOf<NtfyEvent>()
    val results = baseResults.map { base ->
        val prev = prevById[base.id]
        if (prev != null) {
            val becameYes = effectiveAnswer(base, today).startsWith("Yes") &&
                !effectiveAnswer(prev, today).startsWith("Yes")
            val becameDeath = base.tone == "death" && prev.tone != "death"
            if (becameYes || becameDeath) {
                val message = base.detail?.let { stripHtml(it) }?.ifBlank { null }
                    ?: if (becameDeath) "Looks like they're out." else "It's out!"
                transitions += NtfyEvent(base.id, base.label, base.category, message, becameDeath)
            }
        }
        base.copy(since = resolveSince(prev, base, seedById[base.id], today))
    }

    if (ntfyPrefix == null) {
        println("NTFY_TOPIC_PREFIX not set — skipping notifications.")
    } else if (transitions.isNotEmpty()) {
        println("Sending notifications for ${transitions.size} change(s)…")
        transitions.take(8).forEach { ev ->
            // Each change fans out to its item topic, its category firehose, and the global one.
            ntfyTopicsFor(ntfyPrefix, ev.category, ev.id).forEach { topic ->
                sendNtfy(client, ntfyServer, topic, ev.label, ev.message, ev.death)
            }
        }
    }

    client.close()

    val output = OutputData(updated = Instant.now().toString(), items = results)
    val json = Json { prettyPrint = true }
    File(outputPath).writeText(json.encodeToString(output))

    println("\nWrote $outputPath:")
    results.forEach { r ->
        val note = r.detail?.let { " ($it)" } ?: ""
        println("  [${r.category}] ${r.label}: ${r.answer}$note")
    }

    exitProcess(0)
}
