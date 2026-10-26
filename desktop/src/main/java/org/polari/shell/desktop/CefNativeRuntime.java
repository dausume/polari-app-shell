package org.polari.shell.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import me.friwi.jcefmaven.CefBuildInfo;

/** Select the shared packaged browser before considering a per-user download. */
final class CefNativeRuntime {
    record Selection(Path directory, boolean bundled) {}

    static Selection select(Path share) throws IOException {
        return select(share, Path.of("/opt/polari-shell/lib/jcef-natives"),
                Path.of("/etc/polari/install-mode"));
    }

    static Selection select(Path share, Path bundled, Path mode)
            throws IOException {
        if (Files.exists(bundled)) {
            for (String file : new String[]{"libcef.so", "libjcef.so",
                    "jcef_helper", "icudtl.dat", "resources.pak",
                    "build_meta.json"}) {
                if (!Files.isRegularFile(bundled.resolve(file))) {
                    throw new IOException("Bundled browser is incomplete: " + file);
                }
            }
            CefBuildInfo packaged = CefBuildInfo.fromFile(
                    bundled.resolve("build_meta.json").toFile());
            if (!CefBuildInfo.fromClasspath().getReleaseTag()
                    .equals(packaged.getReleaseTag())
                    || !"linux-amd64".equals(packaged.getPlatform())) {
                throw new IOException("Bundled browser does not match this shell release");
            }
            return new Selection(bundled, true);
        }
        if (Files.isRegularFile(mode)
                && "offline".equals(Files.readString(mode).trim())) {
            throw new IOException("Offline browser runtime is missing. "
                    + "Install the complete offline medium; no download attempted.");
        }
        return new Selection(share.resolve("jcef-natives"), false);
    }

    private CefNativeRuntime() {}
}
