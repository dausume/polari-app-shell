// Root build: shared Java config + the srcDistTar the App Store
// serves. The store OVERLAYS config/polari-shell.json + README.md
// into this archive per download (appstore.shell_project) — it never
// rebuilds it, so the archive must be deterministic here too.

plugins {
    base
}

subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            // Java 17 bytecode floor: everything :core needs
            // (records, switch expressions, java.net.http) is 17-
            // clean, and 17 is what the Android toolchain (D8/AGP)
            // consumes without ceremony — :android reuses :core
            // directly. Any JDK >= 17 builds the tarball.
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(17)
            options.encoding = "UTF-8"
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}

// The deterministic source archive (plan B5): exactly what a build
// needs, nothing generated, fixed timestamps + stable order so the
// same tree always produces the same bytes for the store to overlay.
tasks.register<Tar>("srcDistTar") {
    archiveFileName.set("polari-app-shell-src.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    compression = Compression.GZIP
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    into("polari-app-shell") {
        from(layout.projectDirectory) {
            include(
                "settings.gradle.kts", "build.gradle.kts",
                "gradle.properties",
                "gradle/**", "gradlew", "gradlew.bat",
                "config/**", "docs/**", "README.md",
                "core/build.gradle.kts", "core/src/**",
                "desktop/build.gradle.kts", "desktop/src/**",
            )
            exclude("**/build/**", "**/.gradle/**")
        }
    }
}
