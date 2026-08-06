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
            // Any JDK >= 21 builds this (Java 22 on the dev box);
            // release=21 keeps end-user tarball builds honest about
            // the floor without forcing a toolchain download.
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(21)
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
