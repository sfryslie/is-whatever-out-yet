import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
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

    /** Flips to "Yes." once the wall clock passes the scheduled date. */
    data class ScheduledDate(val date: LocalDate) : Check()

    /**
     * Like ScheduledDate but the public-facing label is fuzzy ("January 2027", "Late 2026?").
     * Underlying date is still used as the flip trigger and to drive the rough countdown.
     */
    data class VagueDate(val date: LocalDate, val vagueLabel: String) : Check()

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
    ) : Check()

    /**
     * Like [WikipediaLead] but fetches the full rendered HTML (not just the summary extract),
     * so it can see infobox fields the summary endpoint strips. Case-sensitive substring match
     * — infobox values have predictable capitalization (e.g. "Incarcerated at") that body
     * prose typically doesn't. Phrase missing → "Maybe?" with [flippedDetail] + Wikipedia link
     * (intentionally not a confident "Yes." — infobox changes can be template churn or transfer
     * notation, not just release).
     */
    data class WikipediaHtml(
        val article: String,
        val phrase: String,
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
)

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

    // Games
    Item("half-life-3",     "Half-Life 3",     "Game", Check.Hardcoded, "No."),
    Item("ricochet-2",      "Ricochet 2",      "Game", Check.Hardcoded, "No."),
    Item("team-fortress-3", "Team Fortress 3", "Game", Check.Hardcoded, "No.",  "<a href=\"https://store.steampowered.com/app/3545060/Team_Fortress_2_Classified/\" target=\"_blank\" rel=\"noopener\">TF2 Classified is kinda fun, though.</a>"),
    Item("palworld-1",      "Palworld 1.0",    "Game", Check.ScheduledDate(LocalDate.of(2026, 7, 10))),
    Item("valheim-1",       "Valheim Deep North",     "Game", Check.ScheduledDate(LocalDate.of(2026, 9, 9))),
    Item("deltarune-ch5",   "Deltarune Ch. 5", "Game", Check.Hardcoded, "Yes.",   "Released June 24, 2026."),
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

    // Books
    Item("winds-of-winter", "The Winds of Winter",      "Book", Check.Hardcoded, "No.", "GRRM started writing it in 2010. Watch the show again."),
    Item("dsm-6",           "DSM-6",                    "Book", Check.Hardcoded, "No.", "AI <a href=\"https://en.wikipedia.org/wiki/Chatbot_psychosis\" target=\"_blank\" rel=\"noopener\">chatbot psychosis</a> will likely be in there."),

    // Shows
    Item("rezero-s4-cour2",     "Re:Zero S4 Cour 2",      "Show", Check.ScheduledDate(LocalDate.of(2026, 8, 12))),
    Item("jjk-s4",              "Jujutsu Kaisen S4",      "Show", Check.VagueDate(LocalDate.of(2027, 1, 31), "January 2027?")),
    Item("steel-ball-run-ep2",  "Steel Ball Run Ep. 2",   "Show", Check.VagueDate(LocalDate.of(2026, 12, 31), "Late 2026?"),
        defaultDetail = "Fuck Netflix."),
    Item("shangri-la-s3",       "Shangri-La Frontier S3", "Show", Check.VagueDate(LocalDate.of(2027, 1, 31), "January 2027")),
    Item("frieren-s3",          "Frieren S3",             "Show", Check.VagueDate(LocalDate.of(2027, 10, 31), "October 2027?")),
    Item("dbs-galactic-patrol", "Dragon Ball Super: The Galactic Patrol", "Show", Check.VagueDate(LocalDate.of(2027, 12, 31), "Late 2027?")),

    // Movies (date-ordered)
    Item("dune-3",              "Dune: Part Three",       "Movie", Check.ScheduledDate(LocalDate.of(2026, 12, 18))),
    Item("avengers-doomsday",   "Avengers: Doomsday",     "Movie", Check.ScheduledDate(LocalDate.of(2026, 12, 18))),
    Item("air-bud-returns",     "Air Bud Returns",        "Movie", Check.ScheduledDate(LocalDate.of(2027, 1, 22))),
    Item("sonic-4",             "Sonic the Hedgehog 4",   "Movie", Check.ScheduledDate(LocalDate.of(2027, 3, 19))),
    Item("zelda-movie",         "The Legend of Zelda",    "Movie", Check.ScheduledDate(LocalDate.of(2027, 4, 30)),
        defaultDetail = "I'm morbidly curious."),
    Item("starwars-starfighter", "Star Wars: Starfighter", "Movie", Check.ScheduledDate(LocalDate.of(2027, 5, 28)),
        defaultDetail = "It has Ryan Gosling, I guess? Did anyone actually go to the Mandalorian movie?"),
    Item("spiderverse-3",       "Spider-Man: Beyond the Spider-Verse", "Movie", Check.ScheduledDate(LocalDate.of(2027, 6, 18))),
    Item("shrek-5",             "Shrek 5",                "Movie", Check.ScheduledDate(LocalDate.of(2027, 6, 30))),
    Item("demon-slayer-ic-2",   "Demon Slayer: Infinity Castle Part 2", "Movie", Check.VagueDate(LocalDate.of(2027, 9, 22), "Summer 2027?")),
    Item("the-batman-2",        "The Batman Part II",     "Movie", Check.ScheduledDate(LocalDate.of(2027, 10, 1))),
    Item("frozen-3",            "Frozen III",             "Movie", Check.ScheduledDate(LocalDate.of(2027, 11, 24))),
    Item("lotr-gollum",         "The Lord of the Rings: The Hunt for Gollum", "Movie", Check.ScheduledDate(LocalDate.of(2027, 12, 17))),
    Item("avengers-secret-wars", "Avengers: Secret Wars", "Movie", Check.ScheduledDate(LocalDate.of(2027, 12, 17))),
    Item("avatar-4",            "Avatar 4: The Tulkun Rider", "Movie", Check.ScheduledDate(LocalDate.of(2029, 12, 21))),

    // People
    Item("diddy",           "Diddy",           "People",
        Check.WikipediaHtml("Sean_Combs", "Incarcerated at", flippedDetail ="He's out."),
        defaultDetail = "Serving ~50 months in prison."),
    Item("henry-kissinger", "Henry Kissinger", "People", Check.Hardcoded, "Maybe?", "I think he's still in one of those Myst books?"),
    Item("donald-trump",    "Donald Trump",    "People",
        Check.WikipediaLead("Donald_Trump", "is the 47th president", LocalDate.of(2029, 1, 20)),
        defaultDetail = "Still the 47th president."),
    Item("vladimir-putin",  "Vladimir Putin",  "People",
        Check.WikipediaLead("Vladimir_Putin", "President of Russia since"),
        defaultDetail = "Still President of Russia. Has been since 2012."),
    Item("elizabeth-holmes", "Elizabeth Holmes", "People",
        Check.WikipediaHtml("Elizabeth_Holmes", "Incarcerated at", flippedDetail ="She's out."),
        defaultDetail = "Serving 11+ years at FPC Bryan."),
    Item("sbf",              "Sam Bankman-Fried", "People",
        Check.WikipediaHtml("Sam_Bankman-Fried", ">Imprisoned<", flippedDetail ="He's out."),
        defaultDetail = "25 years at FCI Lompoc I. Don't hold your breath."),

    // Resources
    Item("helium",          "Helium",          "Resource", Check.Hardcoded, "No.",  "~200 years of supply remaining. Don't panic."),
    Item("ram",             "RAM",             "Resource", Check.Hardcoded, "Probably.",  "Blame AI."),
    Item("toilet-paper",    "Toilet Paper",    "Resource", Check.Hardcoded, "No.",  "Honestly, just get a <a href=\"https://www.costco.com/p/-/toto-drake-2-piece-elongated-toilet-with-c5-washlet-bidet-seat/4000380465\" target=\"_blank\" rel=\"noopener\">Toto bidet from Costco.</a> Y'know, with like a heated seat and warm water."),

    // Tech
    Item("tesla-roadster-2", "Tesla Roadster 2", "Tech",
        Check.WikipediaLead("Tesla_Roadster_(second_generation)", "is an upcoming"),
        defaultDetail = "Announced November 2017. Still upcoming."),

    // Internet
    Item("sbemail-211",     "Sbemail 211",     "Internet", Check.HomestarRunner),
)

// ── Match helpers ─────────────────────────────────────────────────────────────

private val PREVIEW_SUFFIXES = listOf("-preview", "-experimental", "-exp", "-beta", "-alpha")
private fun String.isPreviewVariant() = PREVIEW_SUFFIXES.any { contains(it, ignoreCase = true) }

private fun matchModelId(ids: List<String>, pattern: String): String? =
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

// ── Main ──────────────────────────────────────────────────────────────────────

fun main(): Unit = runBlocking {
    val anthropicKey = System.getenv("ANTHROPIC_API_KEY") ?: error("ANTHROPIC_API_KEY not set")
    val openAiKey    = System.getenv("OPENAI_API_KEY")    // optional
    val googleKey    = System.getenv("GOOGLE_API_KEY")    // optional
    val xaiKey       = System.getenv("XAI_API_KEY")       // optional
    val outputPath   = System.getenv("DATA_JSON_PATH")    ?: "../data.json"

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

    val results = ITEMS.map { item ->
        when (val check = item.check) {
            is Check.Hardcoded -> ItemResult(item.id, item.label, item.category, item.defaultAnswer, item.defaultDetail)

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

            is Check.WikipediaLead -> {
                println("Checking Wikipedia lead for ${check.article}…")
                val extract = fetchWikipediaExtract(client, check.article)
                if (extract == null || extract.contains(check.phrase, ignoreCase = true)) {
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
                        answer = "Yes.",
                        detail = "${escapeHtmlText(extract)} <a href=\"$articleUrl\" target=\"_blank\" rel=\"noopener\">(Wikipedia)</a>",
                    )
                }
            }

            is Check.WikipediaHtml -> {
                println("Checking Wikipedia HTML for ${check.article}…")
                val html = fetchWikipediaHtml(client, check.article)
                if (html == null || html.contains(check.phrase)) {
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
