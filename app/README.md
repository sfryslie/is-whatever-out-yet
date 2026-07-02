# iwoy? — the app

Kotlin Multiplatform + Compose Multiplatform version of [iswhateveroutyet.com](https://iswhateveroutyet.com).
One shared Kotlin UI runs on **Android**, **iOS**, and **desktop (JVM)**. The website and PWA are
untouched — this lives alongside them.

## Architecture

The app is a *client of the same static data* the website reads: it fetches
`https://iswhateveroutyet.com/data/index.json` + the per-category files, so GitHub Pages is
effectively the backend and the checker/GitHub Action pipeline needs no changes. Everything in
`composeApp/src/commonMain` is shared across all three targets:

```
composeApp/
  src/commonMain/kotlin/com/iswhateveroutyet/app/
    model/       ItemResult & friends — mirrors the checker's output shape
    data/        Ktor fetch of data/*.json + the GitHub "last checked" API
    logic/       resolveItem / cardClass / hide-old / sort — a port of index.html's JS
    push/        topic naming (matches the checker) + PushManager + PushPlatform interface
    ui/          all the Compose UI: cards, hero view, search, settings sheet, palettes
  src/androidMain/   MainActivity, FCM service, notification permission flow
  src/iosMain/       MainViewController + the Swift↔Kotlin push bridge
  src/desktopMain/   desktop window (for quick local runs; no push)
iosApp/              Xcode wrapper project (build this on a Mac)
```

Feature parity with the site: category grid with the same card colors (incl. the somber `death`
tone), client-side countdown resolution against the local clock, vague dates, search over labels +
aliases, the 1-result hero view and the 0-result "I don't know.", light/dark theme (follows OS,
overridable), the hide-long-released slider with the same six stops, per-category visibility,
per-item / per-category / everything notification bells, "Last updated" + "Last checked" footer,
IBM Plex Mono throughout, and pull-to-refresh instead of the browser's reload.

## Building

### Android (works on any OS)

```bash
cd app
./gradlew :composeApp:assembleDebug     # APK at composeApp/build/outputs/apk/debug/
./gradlew :composeApp:desktopTest       # unit tests for the ported resolve logic
```

Or open `app/` in Android Studio and hit Run. `local.properties` must point at an Android SDK
(Android Studio writes this for you).

Installing on your own phone is free: enable developer mode + USB debugging and
`adb install composeApp-debug.apk`, or just Run from Android Studio.

### Desktop (handy for eyeballing UI changes without an emulator)

```bash
cd app
./gradlew :composeApp:run
```

### iOS (requires a Mac)

Kotlin/Native cannot cross-compile iOS from Windows/Linux — you need macOS + Xcode. On a Mac:

```bash
open app/iosApp/iosApp.xcodeproj
```

Set your Team ID in `iosApp/Configuration/Config.xcconfig` (or let Xcode manage signing), pick a
simulator, and Run. The Xcode project invokes Gradle to build the Kotlin framework automatically.

## Do I need developer accounts?

| What you want to do | Account needed | Cost |
|---|---|---|
| Run on your own Android phone / emulator | none | free |
| Publish to Google Play | Google Play Console | $25 one-time |
| Run in the iOS Simulator | none (just a Mac + Xcode) | free |
| Run on your own iPhone | free Apple ID works (app re-signs every 7 days) | free |
| **iOS push notifications (APNs)** — even in development | Apple Developer Program | $99/year |
| Publish to the App Store | Apple Developer Program | $99/year |

So: Android is fully testable (including push, via Firebase's free tier) with no accounts beyond
Firebase. iOS needs a Mac to build at all, and the *push* part specifically is gated on the paid
Apple program because APNs certificates/keys only exist inside it.

## Push notifications

Same pipeline as the website, one extra hop: the checker still POSTs each change to the
[Cloudflare Worker](../push-worker)'s `/send`; the Worker now fans out to **both** Web Push
subscriptions (browsers) and native FCM device tokens (this app) with the same topic system —
per-item, per-category, and `iswhateveroutyet-all`. Nothing about the checker changes.

One-time setup:

1. Create a Firebase project (free Spark plan) and add:
   - an **Android app** with package `com.iswhateveroutyet.app` → download `google-services.json`
     into `app/composeApp/` (the Gradle plugin activates automatically when the file exists);
   - an **iOS app** with bundle id `com.iswhateveroutyet.app` → download
     `GoogleService-Info.plist` into `app/iosApp/iosApp/`.
2. Give the Worker send access: Firebase console → Project settings → Service accounts →
   Generate new private key, then set `FCM_PROJECT_ID` / `FCM_CLIENT_EMAIL` / `FCM_PRIVATE_KEY`
   as Worker secrets (see [push-worker/README.md](../push-worker/README.md)).
3. iOS only: add the firebase-ios-sdk package + Push Notifications capability in Xcode, upload
   your APNs key to Firebase, and uncomment the `PUSH:` lines in `iosApp/iosApp/iOSApp.swift`.
   (Requires the paid Apple Developer Program.)

Built without Firebase config, the app still works — the bells are hidden at runtime, exactly like
the website hides its bells when its push Worker isn't configured.

**Desktop** is different: no push service (FCM, APNs, Web Push) covers plain JVM apps, so the
desktop bells work without any registration — topics are stored locally and a poller
(`watch/ReleaseWatcher.kt`) checks the site data every 15 minutes, raising a system-tray
notification when a subscribed item flips to "out" (or to the death tone). Closing the window
minimizes to the tray so the watcher keeps running; quit via the tray menu's Exit. The obvious
limitation vs real push: nothing arrives while the app isn't running.

## Ideas that would differentiate the app from the site

Not built (yet), but this codebase is set up so they'd slot in:

- **Home-screen widgets** — a single card ("is GTA VI out yet? No. 342 days") via Glance
  (Android) / WidgetKit (iOS). The shared `resolveItem` logic already produces exactly that text.
- **iOS Live Activities** — a release-day countdown on the lock screen / Dynamic Island.
- **Wear OS / watchOS complication** — the single most important answer, on your wrist.
- **Offline-first** — cache the last data snapshot locally (the PWA already does this; the app
  currently just refetches).
- **A "wall of shame" sort** — most-delayed items, powered by the git history of `data/`.
