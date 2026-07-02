package com.iswhateveroutyet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.iswhateveroutyet.app.push.AndroidPushPlatform
import com.iswhateveroutyet.app.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Must be constructed before setContent — it registers an activity-result launcher
        // for the POST_NOTIFICATIONS permission prompt.
        val push = AndroidPushPlatform(this)
        setContent {
            App(pushPlatform = push)
        }
    }
}
