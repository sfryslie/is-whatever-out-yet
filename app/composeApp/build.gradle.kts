import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
    id("com.android.application")
}

// Firebase (Android push) is opt-in: drop your google-services.json next to this file and the
// plugin activates. Without it the app still builds — the bells just stay hidden at runtime,
// same as the website when push isn't configured.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val ktorVersion = "3.1.3"
val coroutinesVersion = "1.10.2"

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop")

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
            implementation("io.ktor:ktor-client-core:$ktorVersion")
            implementation("com.russhwolf:multiplatform-settings-no-arg:1.3.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.10.1")
            // Not used directly — pins the transitive fragment version above 1.3.0 so release
            // lint's InvalidFragmentVersionForActivityResult (fatal) doesn't trip on the
            // permission launcher in AndroidPushPlatform.
            implementation("androidx.fragment:fragment:1.8.5")
            implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
            implementation("com.google.firebase:firebase-messaging:24.1.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:$coroutinesVersion")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:$ktorVersion")
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("io.ktor:ktor-client-cio:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:$coroutinesVersion")
            }
        }
    }
}

compose.resources {
    packageOfResClass = "com.iswhateveroutyet.app.resources"
}

android {
    namespace = "com.iswhateveroutyet.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.iswhateveroutyet.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        // Optional release signing driven by env vars (set by the app-release workflow from repo
        // secrets). Without them the release build falls back to the debug key below, so a
        // CI-built "release" APK is still installable — just not Play-Store-uploadable.
        System.getenv("ANDROID_KEYSTORE_PATH")?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

compose.desktop {
    application {
        mainClass = "com.iswhateveroutyet.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "iwoy"
            packageVersion = "1.0.0"
        }
        buildTypes.release.proguard {
            // packageRelease* installers without proguard — not worth maintaining keep-rules
            // for ktor/serialization reflection just to shave a few MB off a joke app.
            isEnabled.set(false)
        }
    }
}
