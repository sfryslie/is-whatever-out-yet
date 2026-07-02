package com.iswhateveroutyet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.iswhateveroutyet.app.push.AndroidPushPlatform
import com.iswhateveroutyet.app.ui.App
import com.iswhateveroutyet.app.watch.WatchWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Must be constructed before setContent — it registers an activity-result launcher
        // for the POST_NOTIFICATIONS permission prompt.
        val push = AndroidPushPlatform(this)
        // No Firebase config → notifications come from the local background watcher instead of FCM.
        if (!push.usesServerRegistration) WatchWorker.schedule(this)
        setContent {
            App(pushPlatform = push)
        }
    }
}
