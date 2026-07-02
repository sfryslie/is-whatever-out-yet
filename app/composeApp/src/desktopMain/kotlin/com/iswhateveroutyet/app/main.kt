package com.iswhateveroutyet.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.iswhateveroutyet.app.resources.Res
import com.iswhateveroutyet.app.resources.app_icon
import com.iswhateveroutyet.app.ui.App
import org.jetbrains.compose.resources.painterResource

// Desktop target exists mostly so the shared UI can be run and eyeballed without an
// emulator — no push here (bells stay hidden), same as a browser without push support.
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "is whatever out yet?",
        icon = painterResource(Res.drawable.app_icon), // else the taskbar shows Duke
        state = rememberWindowState(width = 1100.dp, height = 820.dp),
    ) {
        App()
    }
}
