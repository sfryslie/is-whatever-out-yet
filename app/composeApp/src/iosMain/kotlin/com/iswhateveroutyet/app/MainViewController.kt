package com.iswhateveroutyet.app

import androidx.compose.ui.window.ComposeUIViewController
import com.iswhateveroutyet.app.push.IosPushPlatform
import com.iswhateveroutyet.app.ui.App
import platform.UIKit.UIViewController

/** Entry point called from Swift (iosApp/iosApp/ContentView.swift). */
fun MainViewController(): UIViewController = ComposeUIViewController {
    App(pushPlatform = IosPushPlatform())
}
