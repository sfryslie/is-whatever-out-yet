import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

// ── Wikipedia REST API ────────────────────────────────────────────────────────

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
