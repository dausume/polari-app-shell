package org.polari.shell.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.polari.shell.core.config.ConfigLoader;
import org.polari.shell.core.config.ShellConfig;
import org.polari.shell.core.registry.InstanceRegistry;

class ConfigAndRegistryTest {

    private static final String DOC = """
        {"kind": "polari-shell-registration", "schemaVersion": 1,
         "app": {"name": "s", "scope": "instance"},
         "instances": [{"id": "prf-a",
                        "webUrl": "https://prf.test",
                        "apiUrl": "https://api.prf.test"}]}
        """;

    @Test
    void precedenceExplicitBeatsSidecar(@TempDir Path dir)
            throws Exception {
        Path explicit = dir.resolve("explicit.json");
        Files.writeString(explicit, DOC.replace("prf-a", "from-arg"));
        Path launcherDir = dir.resolve("launcher");
        Files.createDirectories(launcherDir);
        Files.writeString(launcherDir.resolve("polari-shell.json"),
                DOC.replace("prf-a", "from-sidecar"));
        ShellConfig cfg = ConfigLoader.load(explicit, launcherDir)
                .orElseThrow();
        assertEquals("from-arg", cfg.instances.get(0).id);
        // Without the arg, the sidecar wins.
        assertEquals("from-sidecar",
                ConfigLoader.load(null, launcherDir)
                        .orElseThrow().instances.get(0).id);
    }

    @Test
    void invalidDocumentsRefuseQuietly() {
        assertTrue(ConfigLoader.parse("{\"kind\": \"other\"}")
                .isEmpty());
        assertTrue(ConfigLoader.parse("not json").isEmpty());
    }

    @Test
    void registryMergeIsAddOnly(@TempDir Path dir) throws Exception {
        InstanceRegistry reg = new InstanceRegistry(
                dir.resolve("instances.json"));
        ShellConfig first = ConfigLoader.parse(DOC).orElseThrow();
        assertEquals(List.of("prf-a"), reg.merge(first, "baked"));
        // Same id, same URL again -> nothing added, no conflict.
        assertEquals(List.of(),
                reg.merge(ConfigLoader.parse(DOC).orElseThrow(),
                        "deeplink"));
        assertTrue(reg.conflicts().isEmpty());
        // Same id, DIFFERENT url -> conflict recorded, not
        // overwritten (add-only contract).
        ShellConfig hostile = ConfigLoader.parse(
                DOC.replace("https://prf.test",
                        "https://evil.example")).orElseThrow();
        assertEquals(List.of(), reg.merge(hostile, "deeplink"));
        assertEquals(1, reg.conflicts().size());
        assertEquals("https://prf.test",
                reg.get("prf-a").orElseThrow().config.webUrl);
        // Round-trips through disk.
        reg.markUsed("prf-a");
        reg.save();
        InstanceRegistry again = new InstanceRegistry(
                dir.resolve("instances.json"));
        again.load();
        assertEquals("prf-a", again.lastUsedId());
        assertEquals(1, again.all().size());
    }
}
