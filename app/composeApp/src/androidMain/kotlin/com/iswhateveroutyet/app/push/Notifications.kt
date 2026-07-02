package com.iswhateveroutyet.app.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.iswhateveroutyet.app.MainActivity
import com.iswhateveroutyet.app.R

private const val CHANNEL_ID = "release-updates"

/**
 * Post a release notification — shared by the FCM service and the WorkManager watcher.
 * POST_NOTIFICATIONS only exists on API 33+; on older APIs checkSelfPermission would report
 * an unknown permission as denied, so the check must be SDK-gated.
 */
fun notifyRelease(context: Context, title: String, body: String, tag: String) {
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Release updates", NotificationManager.IMPORTANCE_DEFAULT)
    )
    val intent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    manager.notify(
        tag.hashCode(),
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build(),
    )
}
