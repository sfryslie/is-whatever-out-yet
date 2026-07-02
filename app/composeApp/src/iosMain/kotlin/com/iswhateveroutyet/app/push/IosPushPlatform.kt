package com.iswhateveroutyet.app.push

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Swift wires the actual push transport in here from the AppDelegate (see iosApp/iosApp/iOSApp.swift):
 * requesting UNUserNotificationCenter authorization and handing back the FCM token. Until Swift sets
 * [tokenRequester] (i.e. Firebase isn't configured), `supported` stays false and every bell is hidden.
 */
object IosPushBridge {
    /** Set from Swift: request notification permission, then call the callback with the FCM token (or nil). */
    var tokenRequester: ((callback: (String?) -> Unit) -> Unit)? = null
}

class IosPushPlatform : PushPlatform {
    override val supported: Boolean get() = IosPushBridge.tokenRequester != null
    override val platformName = "ios"

    override suspend fun requestToken(): String? {
        val requester = IosPushBridge.tokenRequester ?: return null
        return suspendCancellableCoroutine { cont ->
            requester { token -> cont.resume(token) }
        }
    }
}
