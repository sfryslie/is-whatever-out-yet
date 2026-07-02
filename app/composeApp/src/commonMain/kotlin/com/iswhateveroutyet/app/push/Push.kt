package com.iswhateveroutyet.app.push

import com.iswhateveroutyet.app.model.ItemResult
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Must stay in sync with TOPIC_PREFIX in index.html and Push.kt in the checker — a card's topic
// here has to equal what the checker posts to the Worker's /send.
const val PUSH_API = "https://iswhateveroutyet-push.iswhateveroutyet-push.workers.dev"
const val TOPIC_PREFIX = "iswhateveroutyet"

fun catSlug(category: String): String =
    category.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

fun topicItem(item: ItemResult) = "$TOPIC_PREFIX-${catSlug(item.category)}-${item.id}"
fun topicCat(category: String) = "$TOPIC_PREFIX-${catSlug(category)}-all"
val TOPIC_ALL = "$TOPIC_PREFIX-all"

/** Whether the subscribed [topics] cover this item — itself, its category firehose, or everything. */
fun subscribesTo(item: ItemResult, topics: Set<String>): Boolean =
    topicItem(item) in topics || topicCat(item.category) in topics || TOPIC_ALL in topics

/**
 * Per-platform push transport. Android backs this with Firebase Messaging; iOS with an
 * FCM token bridged in from Swift; desktop has no push *service* (FCM/Web Push don't cover
 * JVM apps), so it flags [usesServerRegistration] = false and a local poller
 * (watch/ReleaseWatcher.kt) delivers tray notifications instead.
 */
interface PushPlatform {
    val supported: Boolean
    /** "android" or "ios" — stored by the Worker alongside the token. */
    val platformName: String
    /**
     * True when toggling a bell must register the device with the push Worker (FCM platforms).
     * False when topics are only consumed locally (the desktop watcher) — no token, no network.
     */
    val usesServerRegistration: Boolean get() = true
    /** Ask for notification permission if needed and return the device's FCM token, or null. */
    suspend fun requestToken(): String?
}

object DisabledPushPlatform : PushPlatform {
    override val supported = false
    override val platformName = "none"
    override suspend fun requestToken(): String? = null
}

@Serializable
data class RegisterNativeBody(val token: String, val platform: String, val topics: List<String>)

@Serializable
data class UnregisterNativeBody(val token: String)

/**
 * Owns the locally persisted topic set (same "pushTopics" key the website uses in localStorage)
 * and keeps the Worker's registration for this device in sync with it.
 */
class PushManager(
    private val platform: PushPlatform,
    private val settings: Settings,
    private val client: HttpClient,
) {
    private val _topics = MutableStateFlow(loadTopics())
    val topics: StateFlow<Set<String>> = _topics

    val enabled: Boolean get() = platform.supported

    private fun loadTopics(): Set<String> =
        settings.getStringOrNull(KEY)?.let {
            try {
                Json.decodeFromString<List<String>>(it).toSet()
            } catch (e: Exception) {
                emptySet()
            }
        } ?: emptySet()

    /** Toggle a topic on/off; returns false if permission was denied or the network call failed. */
    suspend fun toggle(topic: String): Boolean {
        if (!platform.supported) return false
        val next = _topics.value.toMutableSet().apply { if (!add(topic)) remove(topic) }
        if (platform.usesServerRegistration) {
            val token = platform.requestToken() ?: return false
            try {
                if (next.isEmpty()) {
                    client.post("$PUSH_API/unregister-native") {
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(UnregisterNativeBody(token)))
                    }
                } else {
                    client.post("$PUSH_API/register-native") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            Json.encodeToString(
                                RegisterNativeBody(token, platform.platformName, next.toList())
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                return false
            }
        }
        _topics.value = next
        settings.putString(KEY, Json.encodeToString(next.toList()))
        return true
    }

    companion object {
        const val KEY = "pushTopics"
    }
}
