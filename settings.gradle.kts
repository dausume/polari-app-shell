// Polari App Shell — ONE Gradle repo, platform-specific modules
// (the committed direction: JavaFX everywhere, JCEF on desktop,
// Android WebView / iOS WKWebView on mobile, one JSON bridge).
rootProject.name = "polari-app-shell"

dependencyResolutionManagement {
    repositoriesMode.set(
        RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // jogamp transitive natives for jcefmaven
        maven("https://jogamp.org/deployment/maven")
    }
}

include(":core")
include(":desktop")

// :android is only included when an Android SDK is actually present —
// a store-generated source tarball must build :desktop on a machine
// with no Android tooling without AGP even loading.
if (file("local.properties").exists()
    || System.getenv("ANDROID_HOME") != null) {
    include(":android")
}
