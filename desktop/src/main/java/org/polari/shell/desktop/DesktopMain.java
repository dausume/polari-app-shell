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
        ConfigLoader.load(explicit, launcherDir).ifPresent(cfg -> {
            report("config",
                    registry.merge(cfg, explicit != null
                            ? "arg" : "baked/sidecar"), cfg);
            if (!cfg.instances.isEmpty()) {
                preferredId[0] = cfg.instances.get(0).id;
            }
        });

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
                registry, inst, probe, all).open());
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
