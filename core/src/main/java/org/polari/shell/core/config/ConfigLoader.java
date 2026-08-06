package org.polari.shell.core.config;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Registration-document discovery, plan B2 precedence — FIRST HIT
 * WINS, later sources never overwrite an already-registered
 * instance (the registry enforces add-only):
 *
 *   1. --config <path>            (dev / test harness)
 *   2. sidecar polari-shell.json  (next to the launcher — the
 *      prebuilt-archive injection path)
 *   3. classpath /config/polari-shell.json (baked by the store's
 *      tarball overlay)
 *
 * Deep links and the manual wizard are runtime sources (4 and 5) —
 * they arrive as documents too, but through DeepLink/UI code, not
 * here.
 */
public final class ConfigLoader {

    private static final Gson GSON = new Gson();

    private ConfigLoader() {}

    public static Optional<ShellConfig> load(Path explicit,
                                             Path launcherDir) {
        Optional<ShellConfig> fromArg = fromFile(explicit);
        if (fromArg.isPresent()) {
            return fromArg;
        }
        if (launcherDir != null) {
            Optional<ShellConfig> sidecar = fromFile(
                    launcherDir.resolve("polari-shell.json"));
            if (sidecar.isPresent()) {
                return sidecar;
            }
        }
        return fromClasspath("/config/polari-shell.json");
    }

    public static Optional<ShellConfig> fromFile(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return parse(Files.readString(path,
                    StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static Optional<ShellConfig> fromClasspath(String resource) {
        try (InputStream in =
                ConfigLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                return Optional.empty();
            }
            ShellConfig cfg = GSON.fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8),
                    ShellConfig.class);
            return validated(cfg);
        } catch (IOException | JsonSyntaxException e) {
            return Optional.empty();
        }
    }

    public static Optional<ShellConfig> parse(String json) {
        try {
            return validated(GSON.fromJson(json, ShellConfig.class));
        } catch (JsonSyntaxException e) {
            return Optional.empty();
        }
    }

    private static Optional<ShellConfig> validated(ShellConfig cfg) {
        return (cfg != null && cfg.looksValid())
                ? Optional.of(cfg) : Optional.empty();
    }
}
