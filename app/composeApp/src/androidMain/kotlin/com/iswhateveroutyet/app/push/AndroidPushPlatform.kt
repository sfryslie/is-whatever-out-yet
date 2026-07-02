package com.iswhateveroutyet.app.push

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await

/**
 * FCM-backed push. `supported` is false when the app was built without a google-services.json
 * (Firebase never initializes), which hides every bell — mirroring the website's behavior
 * when its push Worker isn't configured.
 */
class AndroidPushPlatform(private val activity: ComponentActivity) : PushPlatform {

    override val supported: Boolean = FirebaseApp.getApps(activity).isNotEmpty()
    override val platformName = "android"

    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private val permissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingPermission?.complete(granted)
        }

    override suspend fun requestToken(): String? {
        if (!supported) return null
        if (Build.VERSION.SDK_INT >= 33) {
            val has = ContextCompat.checkSelfPermission(
                activity, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!has) {
                val deferred = CompletableDeferred<Boolean>()
                pendingPermission = deferred
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                if (!deferred.await()) return null
            }
        }
        return try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: Exception) {
            null
        }
    }
}
