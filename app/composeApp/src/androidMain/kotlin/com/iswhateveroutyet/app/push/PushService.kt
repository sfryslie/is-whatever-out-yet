package com.iswhateveroutyet.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.russhwolf.settings.Settings
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PushService : FirebaseMessagingService() {

    /** FCM rotated the token — re-register it with whatever topics this device had. */
    override fun onNewToken(token: String) {
        val topics: List<String> = Settings().getStringOrNull(PushManager.KEY)?.let {
            try {
                Json.decodeFromString<List<String>>(it)
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()
        if (topics.isEmpty()) return

        val body = buildJsonObject {
            put("token", token)
            put("platform", "android")
            put("topics", buildJsonArray { topics.forEach { add(it) } })
        }.toString()

        thread {
            try {
                val conn = URL("$PUSH_API/register-native").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(body.encodeToByteArray()) }
                conn.inputStream.use { it.readBytes() }
                conn.disconnect()
            } catch (e: Exception) {
                // Best effort; the next in-app toggle re-registers anyway.
            }
        }
    }

    /**
     * Foreground delivery — when the app is in the background, FCM's `notification` payload is
     * shown by the system automatically, so this only needs to cover the in-foreground case.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification ?: return
        notifyRelease(
            this,
            notification.title ?: "Is whatever out yet?",
            notification.body ?: "",
            message.data["tag"] ?: notification.tag ?: "iwoy",
        )
    }
}
