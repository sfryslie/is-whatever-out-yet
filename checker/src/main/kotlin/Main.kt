import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.system.exitProcess

// ── Data model ───────────────────────────────────────────────────────────────

@Serializable
data class ItemResult(
    val id: String,
    val label: String,
    val category: String,
    val answer: String,
    val detail: String? = null,
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

    /** Check the Anthropic /v1/models list for a model whose ID contains [pattern]. */
    data class Anthropic(val pattern: String) : Check()

    /** Check the OpenAI /v1/models list. Skipped gracefully if OPENAI_API_KEY is unset. */
    data class OpenAI(val pattern: String) : Check()

    /** Check the Google Generative Language /v1beta/models list. Skipped gracefully if GOOGLE_API_KEY is unset. */
    data class Gemini(val pattern: String) : Check()

    /** Fetch homestarrunner.com/sitemap.xml and look for sbemail211. */
    object HomestarRunner : Check()
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

private val DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy")

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

    // Games
    Item("half-life-3",     "Half-Life 3",     "Game", Check.Hardcoded, "No."),
    Item("ricochet-2",      "Ricochet 2",      "Game", Check.Hardcoded, "No."),
    Item("team-fortress-3", "Team Fortress 3", "Game", Check.Hardcoded, "No.",  "<a href=\"https://store.steampowered.com/app/3545060/Team_Fortress_2_Classified/\" target=\"_blank\" rel=\"noopener\">TF2 Classified is kinda fun, though.</a>"),
    Item("palworld-1",      "Palworld 1.0",    "Game", Check.ScheduledDate(LocalDate.of(2026, 7, 10))),
    Item("valheim-1",       "Valheim Deep North",     "Game", Check.ScheduledDate(LocalDate.of(2026, 9, 9))),
    Item("deltarune-ch5",   "Deltarune Ch. 5", "Game", Check.Hardcoded, "Yes.",   "Released June 24, 2026."),
    Item("deltarune-ch6",   "Deltarune Ch. 6", "Game", Check.Hardcoded, "Chill.", "Chapter 5 just came out. Relax."),
    Item("persona-6",       "Persona 6",       "Game", Check.Hardcoded, "No."),
    Item("persona-4-revival", "Persona 4 Revival", "Game", Check.ScheduledDate(LocalDate.of(2027, 2, 18))),
    Item("gta-vi",          "Grand Theft Auto VI",      "Game", Check.ScheduledDate(LocalDate.of(2026, 11, 19))),
    Item("gta-vi-pc",       "Grand Theft Auto VI (PC)", "Game", Check.Hardcoded, "No.", "Rip"),

    // People
    Item("diddy",           "Diddy",           "People", Check.Hardcoded, "No.", "Serving ~50 months. Not out."),
    Item("henry-kissinger", "Henry Kissinger", "People", Check.Hardcoded, "No.", "Died November 29, 2023. Or trapped in a Myst book."),

    // Resources
    Item("helium",          "Helium",          "Resource", Check.Hardcoded, "No.",  "~200 years of supply remaining. Don't panic."),

    // Internet
    Item("sbemail-211",     "Sbemail 211",     "Internet", Check.HomestarRunner),
)

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

suspend fun fetchOpenAIModelIds(client: HttpClient, apiKey: String): List<String> {
    val body = client.get("https://api.openai.com/v1/models") {
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
    val outputPath   = System.getenv("DATA_JSON_PATH")    ?: "../data.json"
    val today        = LocalDate.now()

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

    val results = ITEMS.map { item ->
        when (val check = item.check) {
            is Check.Hardcoded -> ItemResult(item.id, item.label, item.category, item.defaultAnswer, item.defaultDetail)

            is Check.ScheduledDate -> {
                if (today >= check.date) {
                    ItemResult(item.id, item.label, item.category, "Yes.", "Released ${check.date.format(DATE_FMT)}")
                } else {
                    val days = ChronoUnit.DAYS.between(today, check.date)
                    ItemResult(item.id, item.label, item.category, check.date.format(DATE_FMT), "$days day${if (days == 1L) "" else "s"} to go")
                }
            }

            is Check.Anthropic -> {
                val pat = check.pattern
                val matched = anthropicIds.firstOrNull { it == pat || it.startsWith("$pat-") || it.contains(pat) }
                ItemResult(
                    item.id, item.label, item.category,
                    answer = if (matched != null) "Yes." else item.defaultAnswer,
                    detail = matched ?: item.defaultDetail,
                )
            }

            is Check.OpenAI -> {
                if (openAiKey == null) {
                    ItemResult(item.id, item.label, item.category, item.defaultAnswer, item.defaultDetail)
                } else {
                    val pat = check.pattern
                    val matched = openAiIds.firstOrNull { it == pat || it.startsWith("$pat-") || it.contains(pat) }
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
                    val pat = check.pattern
                    val matched = geminiIds.firstOrNull { it == pat || it.contains(pat) }
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
