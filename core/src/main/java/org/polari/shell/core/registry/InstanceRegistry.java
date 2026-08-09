package org.polari.shell.core.registry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.polari.shell.core.config.ShellConfig;

/**
 * The durable client-side instance list (plan B2): after first-run
 * merge the registry is AUTHORITATIVE — baked/sidecar configs are
 * read-once, so the same binary works forever with zero baked
 * config. Merge is ADD-ONLY: a source can never silently overwrite
 * an existing registration; a same-id-different-URL arrival is
 * recorded as a conflict for the UI to surface.
 *
 * File: $XDG_CONFIG_HOME/polari-shell/instances.json (0600).
 */
public class InstanceRegistry {

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Map<String, RegisteredInstance> instances =
            new LinkedHashMap<>();
    private final List<String> conflicts = new ArrayList<>();
    private String lastUsedId = "";

    public InstanceRegistry(Path file) {
        this.file = file;
    }

    public static Path defaultFile() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdg != null && !xdg.isBlank())
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("polari-shell").resolve("instances.json");
    }

    public synchronized void load() {
        instances.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String json = Files.readString(file,
                    StandardCharsets.UTF_8);
            Persisted p = GSON.fromJson(json, Persisted.class);
            if (p != null && p.instances != null) {
                instances.putAll(p.instances);
                lastUsedId = p.lastUsedId == null ? "" : p.lastUsedId;
            }
        } catch (IOException | JsonSyntaxException e) {
            // A corrupt registry must not brick the shell; the
            // wizard can re-register. Loud on stderr, not fatal.
            System.err.println("[registry] unreadable "
                    + file + ": " + e);
        }
    }

    public synchronized void save() throws IOException {
        Files.createDirectories(file.getParent());
        Persisted p = new Persisted();
        p.instances = new LinkedHashMap<>(instances);
        p.lastUsedId = lastUsedId;
        Files.writeString(file, GSON.toJson(p),
                StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(file,
                    PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX (Windows): ACLs already scope to the user.
        }
    }

    /** Add-only merge of a delivered document. Returns ids actually
     *  added (conflicts + already-known ids are skipped). */
    public synchronized List<String> merge(ShellConfig cfg,
                                           String source) {
        List<String> added = new ArrayList<>();
        if (cfg == null) {
            return added;
        }
        cfg.instances.forEach(inst -> {
            if (inst.id == null || inst.id.isBlank()) {
                return;
            }
            RegisteredInstance existing = instances.get(inst.id);
            if (existing == null) {
                RegisteredInstance reg = new RegisteredInstance();
                reg.config = inst;
                reg.source = source;
                instances.put(inst.id, reg);
                added.add(inst.id);
            } else if (!existing.config.webUrl.equals(inst.webUrl)) {
                conflicts.add(inst.id + ": registered "
                        + existing.config.webUrl + " vs arriving "
                        + inst.webUrl + " (" + source + ")");
            } else {
                // Same instance, same URL: the arriving config is a
                // REFRESH of its own registration (a launcher's own
                // --config/sidecar is authoritative for its
                // instance) — update the config so field changes
                // (identity/probe/CA) actually take, keeping the
                // runtime state (enrolled + last probe).
                existing.config = inst;
                existing.source = source;
            }
        });
        return added;
    }

    public synchronized Optional<RegisteredInstance> get(String id) {
        return Optional.ofNullable(instances.get(id));
    }

    public synchronized List<RegisteredInstance> all() {
        return new ArrayList<>(instances.values());
    }

    public synchronized List<String> conflicts() {
        return new ArrayList<>(conflicts);
    }

    public synchronized String lastUsedId() {
        return lastUsedId;
    }

    public synchronized void markUsed(String id) {
        lastUsedId = id;
    }

    private static class Persisted {
        Map<String, RegisteredInstance> instances;
        String lastUsedId;
    }

    // TypeToken kept so Gson's generic map shape survives R8/proguard
    // on Android later.
    @SuppressWarnings("unused")
    private static final TypeToken<Map<String, RegisteredInstance>>
            MAP_TYPE = new TypeToken<>() {};
}
