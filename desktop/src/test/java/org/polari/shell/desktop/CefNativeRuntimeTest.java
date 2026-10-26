package org.polari.shell.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import me.friwi.jcefmaven.CefBuildInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CefNativeRuntimeTest {
    @TempDir Path root;

    private Path bundle(String tag, String platform) throws IOException {
        Path bundle = Files.createDirectories(root.resolve("bundle"));
        for (String file : new String[]{"libcef.so", "libjcef.so", "jcef_helper",
                "icudtl.dat", "resources.pak"}) {
            Files.writeString(bundle.resolve(file), "fixture");
        }
        Files.writeString(bundle.resolve("build_meta.json"),
                "{\"jcef_url\":\"fixture\",\"release_url\":\"fixture\",\"release_tag\":\"" + tag + "\",\"platform\":\"" + platform + "\"}");
        return bundle;
    }

    @Test void offlineUsesSharedBundleWithoutCreatingUserCache() throws Exception {
        Path bundle = bundle(CefBuildInfo.fromClasspath().getReleaseTag(), "linux-amd64");
        Path mode = root.resolve("mode"); Files.writeString(mode, "offline\n");
        Path share = root.resolve("user");
        var selection = CefNativeRuntime.select(share, bundle, mode);
        assertTrue(selection.bundled()); assertEquals(bundle, selection.directory());
        assertFalse(Files.exists(share));
    }

    @Test void offlineRefusesMissingBundleInsteadOfDownloading() throws Exception {
        Path mode = root.resolve("mode"); Files.writeString(mode, "offline");
        assertThrows(IOException.class, () -> CefNativeRuntime.select(
                root.resolve("user"), root.resolve("absent"), mode));
    }

    @Test void refusesWrongRelease() throws Exception {
        Path bundle = bundle("different", "linux-amd64");
        assertThrows(IOException.class, () -> CefNativeRuntime.select(
                root.resolve("user"), bundle, root.resolve("mode")));
    }

    @Test void refusesWrongArchitecture() throws Exception {
        Path bundle = bundle(CefBuildInfo.fromClasspath().getReleaseTag(), "linux-arm64");
        assertThrows(IOException.class, () -> CefNativeRuntime.select(
                root.resolve("user"), bundle, root.resolve("mode")));
    }

    @Test void refusesPartialBundleEvenWhenOnline() throws Exception {
        Path bundle = bundle(CefBuildInfo.fromClasspath().getReleaseTag(), "linux-amd64");
        Files.delete(bundle.resolve("libcef.so"));
        assertThrows(IOException.class, () -> CefNativeRuntime.select(
                root.resolve("user"), bundle, root.resolve("mode")));
    }

    @Test void onlineWithoutBundleKeepsExistingDownloadDirectory() throws Exception {
        Path share = root.resolve("user");
        var selection = CefNativeRuntime.select(share, root.resolve("absent"), root.resolve("mode"));
        assertFalse(selection.bundled());
        assertEquals(share.resolve("jcef-natives"), selection.directory());
    }
}
