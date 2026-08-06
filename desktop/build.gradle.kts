// :desktop — JavaFX chrome + JCEF browser (jcefmaven natives fetch
// on first run). Host window is a Swing JFrame: JCEF's component is
// heavyweight AWT and SwingNode cannot host it reliably, so the
// JavaFX chrome rides in a JFXPanel instead (OSR is the documented
// later refinement, not phase 1).

plugins {
    application
    alias(libs.plugins.javafx)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.jcefmaven)
}

// The store's tarball overlay drops the registration document at
// repo-root config/polari-shell.json; baking it onto the classpath
// is what makes a store download launch pre-registered (precedence
// slot 3). Absent file = nothing baked — the generic-shell path.
tasks.processResources {
    from(rootProject.file("config")) {
        include("polari-shell.json")
        into("config")
    }
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls", "javafx.swing")
}

application {
    mainClass.set("org.polari.shell.desktop.DesktopMain")
    applicationDefaultJvmArgs = listOf(
        // JCEF windowed mode is unreliable on Wayland — force X11
        // (XWayland) until that settles (risk R2).
        "-DGDK_BACKEND=x11",
        "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
    )
}
