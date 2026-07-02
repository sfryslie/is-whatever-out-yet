package com.iswhateveroutyet.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.iswhateveroutyet.app.MainActivity
import com.iswhateveroutyet.app.R
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
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Release updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            (message.data["tag"] ?: notification.tag ?: "iwoy").hashCode(),
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(notification.title ?: "Is whatever out yet?")
                .setContentText(notification.body ?: "")
                .setAutoCancel(true)
                .setContentIntent(intent)
                .build(),
        )
    }

    companion object {
        private const val CHANNEL_ID = "release-updates"
    }
}
