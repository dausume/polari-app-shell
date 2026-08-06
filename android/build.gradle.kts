// :android — the phone/VR shell. Two flavors of ONE codebase:
//   phone  embedded WebView renders the instance's web UI
//   vr     Quest 2 / Vive (Android-based headsets): registration +
//          probe happen natively, rendering DELEGATES to Wolvic
//          (com.igalia.wolvic, the WebXR browser) — required, not
//          bundled. Same shell semantics, different browser seam —
//          the BrowserHost idea from :desktop applied to Android.
// Java (not Kotlin) so :core is consumed with zero extra toolchain.

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.polari.shell.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.polari.shell"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    flavorDimensions += "device"
    productFlavors {
        create("phone") {
            dimension = "device"
        }
        create("vr") {
            dimension = "device"
            applicationIdSuffix = ".vr"
            versionNameSuffix = "-vr"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // BuildConfig.FLAVOR is the phone/vr switch.
        buildConfig = true
    }

    // :core's ReachabilityProbe.probe()/EnrollClient reference
    // java.net.http (absent on Android). AndroidHttp replaces those
    // calls; the pure statics (classify/advisory) resolve lazily per
    // method, so the classes load fine — silence the missing-type
    // lint noise, never call the desktop paths.
    lint {
        disable += "InvalidPackage"
    }
}

dependencies {
    implementation(project(":core"))
}
