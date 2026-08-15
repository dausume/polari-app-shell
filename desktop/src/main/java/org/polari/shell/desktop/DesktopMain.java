package org.polari.shell.desktop;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.swing.SwingUtilities;

import org.polari.shell.core.config.ConfigLoader;
import org.polari.shell.core.config.InstanceConfig;
import org.polari.shell.core.config.ShellConfig;
import org.polari.shell.core.link.DeepLink;
import org.polari.shell.core.net.EnrollClient;
import org.polari.shell.core.net.ReachabilityProbe;
import org.polari.shell.core.registry.InstanceRegistry;
import org.polari.shell.core.registry.RegisteredInstance;
import org.polari.shell.core.tls.InstanceTrust;

/**
 * Phase-1 desktop shell (plan B4 sequence):
 *   config precedence -> registry merge -> pick instance ->
 *   reachability probe (advise, never block) -> redeem enrollment
 *   if pending -> CEF loads the instance web UI; the SPA runs its
 *   own Keycloak PKCE flow (Strategy A) — first launch shows the
 *   login once, the persisted per-instance profile makes every
 *   later launch silent.
 *
 * Args: [--config <path>] [--deeplink <polari://...>]
 */
public final class DesktopMain {

    private DesktopMain() {}

    public static void main(String[] args) throws Exception {
        Path explicit = argValue(args, "--config")
                .map(Paths::get).orElse(null);
        Optional<String> deeplink = argValue(args, "--deeplink");

        InstanceRegistry registry = new InstanceRegistry(
                InstanceRegistry.defaultFile());
        registry.load();

        // 1-3. Config discovery + add-only merge. A launcher's own
        // config (explicit --config OR a sidecar next to it) names
        // the instance THIS launcher should open — so each app deb
        // opens ITS app, never whatever the shared registry last
        // used (fixes the store opening prf-a).
        Path launcherDir = Paths.get(
                System.getProperty("app.home",
                        System.getProperty("user.dir")));
        final String[] preferredId = {null};
        final String[] appName = {""};
        // sep-1: the launcher's app block travels to the frame —
        // scope/appName/startRoute shape the URL the shell opens.
        final ShellConfig.App[] appBlock = {new ShellConfig.App()};
        ConfigLoader.load(explicit, launcherDir).ifPresent(cfg -> {
            report("config",
                    registry.merge(cfg, explicit != null
                            ? "arg" : "baked/sidecar"), cfg);
            if (!cfg.instances.isEmpty()) {
                preferredId[0] = cfg.instances.get(0).id;
            }
            appName[0] = cfg.app.name == null ? "" : cfg.app.name;
            appBlock[0] = cfg.app;
        });

        // The RUNNING window must wear the same identity as its
        // launcher: X11 WM_CLASS defaults to the shared main class
        // (org-polari-shell-desktop-DesktopMain), so GNOME shows a
        // generic Java icon for every app. Match the launcher deb's
        // StartupWMClass (= its package name) instead.
        applyWindowClass(launcherPackageName(appName[0]));

        // 4. Deep link (may add another instance or carry a token).
        deeplink.flatMap(DeepLink::parse)
                .filter(p -> p.kind() == DeepLink.Kind.REGISTER)
                .ifPresent(p -> DeepLinkService.register(
                        registry, p));

        registry.conflicts().forEach(c -> System.err.println(
                "[registry] CONFLICT (kept the existing "
                + "registration): " + c));

        if (registry.all().isEmpty()) {
            System.err.println("""
                No registered instances. Provide one of:
                  --config <polari-shell.json>
                  a polari-shell.json next to the launcher
                  --deeplink 'polari://register?...'
                (First-run wizard UI arrives with the packaged
                builds; the store bakes config into downloads.)""");
            System.exit(2);
        }

        // This launcher's own instance wins; else last-used; else
        // the first registered.
        String id = preferredId[0] != null
                ? preferredId[0]
                : (registry.lastUsedId().isBlank()
                        ? registry.all().get(0).config.id
                        : registry.lastUsedId());
        RegisteredInstance inst = registry.get(id)
                .orElse(registry.all().get(0));
        registry.markUsed(inst.config.id);

        // 5. Probe + advisory (advise, never block).
        SSLContext trust = InstanceTrust.sslContext(
                inst.config.tls.caPem);
        ReachabilityProbe.Result probe =
                ReachabilityProbe.probe(inst.config, trust);
        inst.lastProbe.at = java.time.Instant.now().toString();
        inst.lastProbe.state = probe.state();
        inst.lastProbe.evidence = probe.evidence();
        System.out.println("[probe] " + probe.state() + " — "
                + probe.evidence());

        // 6. Redeem a pending enrollment (single-use, server-side).
        redeemIfPending(registry, inst, trust);

        registry.save();

        // 7. Chrome + browser.
        List<RegisteredInstance> all = registry.all();
        SwingUtilities.invokeLater(() -> new ShellFrame(
                registry, inst, probe, all, appBlock[0]).open());
    }

    /** The launcher deb's package name for an app ('polari' →
     *  'isle-app-polari'; the store's config already carries
     *  'isle-app-store'). '' when no config named an app. */
    static String launcherPackageName(String appName) {
        if (appName == null || appName.isBlank()) {
            return "";
        }
        String n = appName.toLowerCase()
                .replaceAll("[^a-z0-9.-]", "-");
        return n.contains("-app-") || n.startsWith("isle-app")
                ? n : "isle-app-" + n;
    }

    /** Set X11 WM_CLASS to the launcher package (the classic AWT
     *  awtAppClassName field — read at first window creation) and
     *  remember it for the frame icon. Best-effort: on failure the
     *  window keeps the generic identity. */
    private static void applyWindowClass(String wmClass) {
        if (wmClass.isBlank()) {
            return;
        }
        System.setProperty("polari.shell.wmclass", wmClass);
        try {
            java.awt.Toolkit tk =
                    java.awt.Toolkit.getDefaultToolkit();
            java.lang.reflect.Field f = tk.getClass()
                    .getDeclaredField("awtAppClassName");
            f.setAccessible(true);
            f.set(tk, wmClass);
        } catch (Exception e) {
            System.err.println("[shell] WM_CLASS not set (" + e
                    + ") — dock icon may be generic");
        }
    }

    static void redeemIfPending(InstanceRegistry registry,
                                RegisteredInstance inst,
                                SSLContext trust) {
        if (inst.enrolled) {
            return;
        }
        Optional<ShellConfig> baked = ConfigLoader.fromClasspath(
                "/config/polari-shell.json");
        String token = baked.map(c -> c.enrollment)
                .map(e -> e.token).orElse("");
        String redeemUrl = baked.map(c -> c.enrollment)
                .map(e -> e.redeemUrl).orElse("");
        if (token.isBlank() || redeemUrl.isBlank()) {
            return; // nothing pending — deep-link/manual path
        }
        EnrollClient.Outcome out = EnrollClient.redeem(
                redeemUrl, token, "desktop-linux-x64",
                System.getProperty("user.name", "desktop"), trust);
        if (out.ok()) {
            inst.enrolled = true;
            registry.merge(out.document(), "redeem");
            System.out.println("[enroll] redeemed — install "
                    + "registered with the instance");
        } else {
            // Advisory, not fatal: the token is single-use, so a
            // second launch of a baked artifact lands here.
            System.err.println("[enroll] " + out.error());
        }
    }

    private static void report(String source, List<String> added,
                               ShellConfig cfg) {
        if (!added.isEmpty()) {
            System.out.println("[" + source + "] registered "
                    + String.join(", ", added));
        }
    }

    private static Optional<String> argValue(String[] args,
                                             String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) {
                return Optional.of(args[i + 1]);
            }
        }
        return Optional.empty();
    }
}
