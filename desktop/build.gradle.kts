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
