import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*

// ── Web Push notifications (via the Cloudflare Worker in push-worker/) ────────

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

/** Lowercase, slug-safe form of a category for use in a topic name (e.g. "Video Games" → "video-games"). */
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

/**
 * Fan out all of a run's [transitions] to their topics. Web Push Worker endpoint + shared send
 * token come from PUSH_API_URL / PUSH_SEND_TOKEN; both must be set to enable notifications, and
 * missing either skips them (e.g. local runs). GitHub Actions sets a `${{ secrets.X }}` env var to
 * an empty string (not an absent var) when the secret isn't configured, so blank counts as unset
 * too — otherwise we'd try to POST to an empty/relative URL and fail noisily instead of skipping
 * cleanly.
 */
internal suspend fun notifyTransitions(client: HttpClient, transitions: List<ChangeEvent>) {
    val pushApi   = System.getenv("PUSH_API_URL")?.takeUnless { it.isBlank() }
    val pushToken = System.getenv("PUSH_SEND_TOKEN")?.takeUnless { it.isBlank() }
    if (pushApi == null || pushToken == null) {
        println("PUSH_API_URL / PUSH_SEND_TOKEN not set — skipping notifications.")
        return
    }
    if (transitions.isEmpty()) return
    println("Sending push for ${transitions.size} change(s)…")
    transitions.take(8).forEach { ev ->
        // The Worker fans each change out to the item, category, and global topics' subscribers.
        sendPush(client, pushApi, pushToken, ev, topicsFor(TOPIC_PREFIX, ev.category, ev.id))
    }
}
