import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

// ── AI model-list APIs (Anthropic / OpenAI / Gemini / xAI) ────────────────────

private val PREVIEW_SUFFIXES = listOf("-preview", "-experimental", "-exp", "-beta", "-alpha")
private fun String.isPreviewVariant() = PREVIEW_SUFFIXES.any { contains(it, ignoreCase = true) }

/** All non-preview ids matching [pattern] — a provider can ship several variants under one name
 *  (e.g. GPT-5.6's Sol/Terra/Luna split), so callers that want to surface all of them use this
 *  instead of [matchModelId]. */
internal fun matchModelIds(ids: List<String>, pattern: String): List<String> =
    ids.filter { !it.isPreviewVariant() && (it == pattern || it.startsWith("$pattern-") || it.contains(pattern)) }

internal fun matchModelId(ids: List<String>, pattern: String): String? =
    matchModelIds(ids, pattern).firstOrNull()

// Every fetcher below returns `null` on a failed fetch, which is deliberately NOT the same thing
// as an empty list. Empty means "the catalogue came back fine and doesn't list that model" → the
// item resolves to its default "No.". Null means "we never got a usable catalogue" → the caller
// holds the previous run's answer instead.
//
// Getting that distinction wrong is worse in both directions, so both are guarded here:
//   - Not catching at all lets one provider kill the whole run. A plain-text "error code: 504"
//     from OpenAI took down the 2026-09-01 run before any data was written.
//   - Catching too broadly is worse still: a 401/429/500 whose body is perfectly well-formed JSON
//     would parse into a catalogue with no "data" key, read as "zero models exist", and silently
//     flip every shipped Claude/GPT card to "No.". So a non-2xx status or a missing model array
//     is a failure, not an empty catalogue.

/** GET [url] and parse it as a JSON object, treating any non-2xx as a hard failure so the caller's
 *  catch turns it into a null catalogue rather than an empty one. */
private suspend fun HttpClient.getModelJson(
    url: String,
    block: HttpRequestBuilder.() -> Unit = {},
): JsonObject {
    val response = get(url) { block() }
    val text = response.bodyAsText()
    check(response.status.isSuccess()) {
        "HTTP ${response.status.value}: ${text.take(200).lines().joinToString(" ")}"
    }
    return Json.parseToJsonElement(text).jsonObject
}

suspend fun fetchAnthropicModelIds(client: HttpClient, apiKey: String): List<String>? = try {
    val ids = mutableListOf<String>()
    var url: String? = "https://api.anthropic.com/v1/models?limit=100"
    while (url != null) {
        val body = client.getModelJson(url) {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
        }
        val data = body["data"]?.jsonArray ?: error("no 'data' array in response")
        data.forEach { m -> ids += m.jsonObject["id"]!!.jsonPrimitive.content }

        url = if (body["has_more"]?.jsonPrimitive?.boolean == true) {
            body["last_id"]?.jsonPrimitive?.contentOrNull
                ?.let { "https://api.anthropic.com/v1/models?limit=100&after_id=$it" }
        } else null
    }
    ids
} catch (e: Exception) {
    println("  Anthropic model list failed: ${e.message}")
    null
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

suspend fun fetchOpenAIModelIds(client: HttpClient, apiKey: String): List<String>? = try {
    val body = client.getModelJson("https://api.openai.com/v1/models") {
        header("Authorization", "Bearer $apiKey")
    }
    val data = body["data"]?.jsonArray ?: error("no 'data' array in response")
    data.map { it.jsonObject["id"]!!.jsonPrimitive.content }
} catch (e: Exception) {
    println("  OpenAI model list failed: ${e.message}")
    null
}

suspend fun fetchXaiModelIds(client: HttpClient, apiKey: String): List<String>? = try {
    val body = client.getModelJson("https://api.x.ai/v1/models") {
        header("Authorization", "Bearer $apiKey")
    }
    val data = body["data"]?.jsonArray ?: error("no 'data' array in response")
    data.map { it.jsonObject["id"]!!.jsonPrimitive.content }
} catch (e: Exception) {
    println("  xAI model list failed: ${e.message}")
    null
}

suspend fun fetchGeminiModelIds(client: HttpClient, apiKey: String): List<String>? = try {
    val ids = mutableListOf<String>()
    var pageToken: String? = null
    var firstPage = true
    do {
        val url = buildString {
            append("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey&pageSize=100")
            if (pageToken != null) append("&pageToken=$pageToken")
        }
        val body = client.getModelJson(url)
        // Only the first page has to carry models — a trailing empty page is legal pagination.
        val models = body["models"]?.jsonArray
            ?: if (firstPage) error("no 'models' array in response") else JsonArray(emptyList())
        models.forEach { m -> ids += m.jsonObject["name"]!!.jsonPrimitive.content }
        pageToken = body["nextPageToken"]?.jsonPrimitive?.contentOrNull
        firstPage = false
    } while (pageToken != null)
    ids
} catch (e: Exception) {
    println("  Gemini model list failed: ${e.message}")
    null
}
