package com.iswhateveroutyet.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.iswhateveroutyet.app.data.WhateverRepository
import com.iswhateveroutyet.app.push.DesktopPushPlatform
import com.iswhateveroutyet.app.resources.Res
import com.iswhateveroutyet.app.resources.app_icon
import com.iswhateveroutyet.app.ui.App
import com.iswhateveroutyet.app.watch.ReleaseWatcher
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import org.jetbrains.compose.resources.painterResource

fun main() = application {
    var windowVisible by remember { mutableStateOf(true) }
    val trayState = rememberTrayState()
    val icon = painterResource(Res.drawable.app_icon)

    // Desktop notifications: no push service covers JVM apps, so a poller watches the same
    // data/ JSON the site serves and raises tray notifications for subscribed topics. Closing
    // the window keeps the app (and the watcher) alive in the tray; Exit lives in the tray menu.
    LaunchedEffect(Unit) {
        ReleaseWatcher(
            repo = WhateverRepository(HttpClient()),
            settings = Settings(),
            notify = { title, message ->
                trayState.sendNotification(Notification(title, message, Notification.Type.Info))
            },
        ).run()
    }

    Tray(
        state = trayState,
        icon = icon,
        tooltip = "is whatever out yet?",
        onAction = { windowVisible = true },
        menu = {
            Item("Open") { windowVisible = true }
            Item("Exit") { exitApplication() }
        },
    )

    Window(
        onCloseRequest = { windowVisible = false }, // minimize to tray; Exit is in the tray menu
        visible = windowVisible,
        title = "is whatever out yet?",
        icon = icon, // else the taskbar shows Duke
        state = rememberWindowState(width = 1100.dp, height = 820.dp),
    ) {
        App(pushPlatform = DesktopPushPlatform)
    }
}
