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
 * Android push with two modes: built with a google-services.json, Firebase initializes and the
 * bells register FCM tokens with the push Worker (real push, arrives with the app closed).
 * Built without it, the bells still work — topics persist locally and WatchWorker polls in the
 * background instead. Either way the POST_NOTIFICATIONS prompt happens on the first bell tap.
 */
class AndroidPushPlatform(private val activity: ComponentActivity) : PushPlatform {

    private val firebaseAvailable = FirebaseApp.getApps(activity).isNotEmpty()

    override val supported = true
    override val platformName = "android"
    override val usesServerRegistration: Boolean get() = firebaseAvailable

    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private val permissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingPermission?.complete(granted)
        }

    override suspend fun ensurePermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true // POST_NOTIFICATIONS doesn't exist yet
        val has = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (has) return true
        val deferred = CompletableDeferred<Boolean>()
        pendingPermission = deferred
        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return deferred.await()
    }

    override suspend fun requestToken(): String? {
        if (!firebaseAvailable) return null
        return try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: Exception) {
            null
        }
    }
}
