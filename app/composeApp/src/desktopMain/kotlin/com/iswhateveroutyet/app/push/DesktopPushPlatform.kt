package com.iswhateveroutyet.app.push

/**
 * Desktop "push": no FCM/Web Push service exists for JVM apps, so the bells persist topics
 * locally (no Worker registration) and ReleaseWatcher + the system tray deliver notifications
 * while the app runs (it keeps running in the tray after the window closes).
 */
object DesktopPushPlatform : PushPlatform {
    override val supported = true
    override val platformName = "desktop"
    override val usesServerRegistration = false
    override suspend fun requestToken(): String? = null
}
