import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

// ── AI model-list APIs (Anthropic / OpenAI / Gemini / xAI) ────────────────────

private val PREVIEW_SUFFIXES = listOf("-preview", "-experimental", "-exp", "-beta", "-alpha")
private fun String.isPreviewVariant() = PREVIEW_SUFFIXES.any { contains(it, ignoreCase = true) }

internal fun matchModelId(ids: List<String>, pattern: String): String? =
    ids.firstOrNull { !it.isPreviewVariant() && (it == pattern || it.startsWith("$pattern-") || it.contains(pattern)) }

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
