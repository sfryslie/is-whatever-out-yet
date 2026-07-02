import SwiftUI
import UserNotifications
import ComposeApp
// PUSH: uncomment after adding the firebase-ios-sdk package (FirebaseMessaging product) in Xcode.
// import FirebaseCore
// import FirebaseMessaging

/// Push notifications are opt-in and need four one-time steps (see app/README.md for details):
///   1. In Xcode: File → Add Package Dependencies → https://github.com/firebase/firebase-ios-sdk
///      (add the FirebaseMessaging product to the iosApp target)
///   2. Drop your GoogleService-Info.plist from the Firebase console into iosApp/iosApp/
///   3. Add the "Push Notifications" capability to the target (requires a paid Apple Developer
///      account) and upload your APNs auth key to the Firebase console
///   4. Uncomment every line below marked PUSH
/// Until then the app runs fine — the notification bells just stay hidden, exactly like the
/// website does when its push Worker isn't configured.
class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // PUSH: FirebaseApp.configure()
        // PUSH: UNUserNotificationCenter.current().delegate = self

        // Wire the Kotlin side's token bridge: ask permission, register with APNs, hand back
        // the FCM token. Kotlin's IosPushPlatform.supported flips true once this is set.
        // PUSH: IosPushBridge.shared.tokenRequester = { callback in
        // PUSH:     UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, _ in
        // PUSH:         guard granted else { callback(nil); return }
        // PUSH:         DispatchQueue.main.async { UIApplication.shared.registerForRemoteNotifications() }
        // PUSH:         Messaging.messaging().token { token, _ in callback(token) }
        // PUSH:     }
        // PUSH: }
        return true
    }

    // PUSH: func application(_ application: UIApplication,
    // PUSH:                  didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
    // PUSH:     Messaging.messaging().apnsToken = deviceToken
    // PUSH: }
}

// PUSH: extension AppDelegate: UNUserNotificationCenterDelegate {
// PUSH:     // Show notifications while the app is foregrounded, same as the Android side.
// PUSH:     func userNotificationCenter(_ center: UNUserNotificationCenter,
// PUSH:                                 willPresent notification: UNNotification,
// PUSH:                                 withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
// PUSH:         completionHandler([.banner, .sound])
// PUSH:     }
// PUSH: }

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
