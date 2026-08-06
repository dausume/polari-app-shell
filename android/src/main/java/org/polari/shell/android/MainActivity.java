package org.polari.shell.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Optional;
import javax.net.ssl.SSLContext;

import org.polari.shell.core.config.ConfigLoader;
import org.polari.shell.core.config.InstanceConfig;
import org.polari.shell.core.config.ShellConfig;
import org.polari.shell.core.link.DeepLink;
import org.polari.shell.core.net.ReachabilityProbe;
import org.polari.shell.core.registry.InstanceRegistry;
import org.polari.shell.core.registry.RegisteredInstance;

/**
 * The Android shell (plan phase 3, pulled forward). Same sequence
 * as desktop: config -> registry (add-only) -> probe -> advisory or
 * open. Flavors differ ONLY at the browser seam:
 *   phone  embedded WebView with per-instance CA pinning
 *   vr     Quest/Vive: delegate to Wolvic (required, not bundled) —
 *          the headset browser with real WebXR, so the suite's XR
 *          pages actually work there.
 *
 * Auth note: phone WebView lets the SPA run its own Keycloak PKCE
 * (Strategy A); the RFC 8252 Custom-Tabs flow is the phase-2
 * Strategy-B upgrade. VR: Wolvic owns the whole web session.
 */
public class MainActivity extends Activity {

    private InstanceRegistry registry;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registry = new InstanceRegistry(
                getFilesDir().toPath().resolve("instances.json"));
        registry.load();

        // 1. Baked asset config (custom builds; store APKs ship
        //    config-free and register via deep link).
        bakedConfig().ifPresent(cfg ->
                registry.merge(cfg, "baked"));

        // 2. polari:// deep link (register + one-time token).
        Uri data = getIntent().getData();
        if (data != null) {
            handleDeepLink(data.toString());
        }
        try {
            registry.save();
        } catch (Exception ignored) {
            // registry persistence failure surfaces on next launch
        }

        if (registry.all().isEmpty()) {
            showMessage("No registered instances",
                    "Open a polari:// registration link from this "
                    + "instance's App Store page, or install a "
                    + "build with a baked polari-shell.json.");
            return;
        }
        String id = registry.lastUsedId().isBlank()
                ? registry.all().get(0).config.id
                : registry.lastUsedId();
        RegisteredInstance inst = registry.get(id)
                .orElse(registry.all().get(0));
        registry.markUsed(inst.config.id);
        launch(inst);
    }

    private void launch(RegisteredInstance inst) {
        showMessage(title(inst), "probing " + inst.config.webUrl
                + " …");
        new Thread(() -> {
            SSLContext trust = AndroidHttp.trustFor(
                    inst.config.tls.caPem);
            ReachabilityProbe.Result probe =
                    AndroidHttp.probe(inst.config, trust);
            inst.lastProbe.state = probe.state();
            inst.lastProbe.evidence = probe.evidence();
            runOnUiThread(() -> {
                if (!ReachabilityProbe.REACHABLE
                        .equals(probe.state())) {
                    advisory(inst, probe);
                    return;
                }
                if ("vr".equals(BuildConfig.FLAVOR)) {
                    openInWolvic(inst);
                } else {
                    openWebView(inst);
                }
            });
        }, "polari-probe").start();
    }

    /** Advisory UX — advises, never blocks (Open anyway stays). */
    private void advisory(RegisteredInstance inst,
                          ReachabilityProbe.Result probe) {
        LinearLayout box = messageBox(title(inst),
                ReachabilityProbe.advisory(inst.config, probe));
        Button retry = new Button(this);
        retry.setText("Retry");
        retry.setOnClickListener(v -> launch(inst));
        Button anyway = new Button(this);
        anyway.setText("Open anyway");
        anyway.setOnClickListener(v -> {
            if ("vr".equals(BuildConfig.FLAVOR)) {
                openInWolvic(inst);
            } else {
                openWebView(inst);
            }
        });
        box.addView(retry);
        box.addView(anyway);
    }

    // ---- phone: embedded WebView --------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private void openWebView(RegisteredInstance inst) {
        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new PinnedClient(inst.config));
        web.addJavascriptInterface(
                new ShellBridge(this, registry, inst),
                "PolariShellNative");
        setContentView(web);
        web.loadUrl(inst.config.webUrl);
    }

    /**
     * Per-instance CA pinning for WebView. API 29+ exposes the real
     * X509Certificate; below that the platform hands us a lossy
     * SslCertificate, so we refuse rather than guess — the phone
     * floor for self-signed instances is effectively API 29.
     */
    private class PinnedClient extends WebViewClient {
        private final InstanceConfig inst;

        PinnedClient(InstanceConfig inst) {
            this.inst = inst;
        }

        @Override
        public void onReceivedSslError(WebView view,
                SslErrorHandler handler, SslError error) {
            try {
                if (Build.VERSION.SDK_INT >= 29
                        && !inst.tls.caPem.isEmpty()) {
                    X509Certificate seen = error.getCertificate()
                            .getX509Certificate();
                    if (seen != null && chainMatches(seen)) {
                        handler.proceed();
                        return;
                    }
                }
            } catch (Exception ignored) {
                // fall through to refusal
            }
            handler.cancel();
            runOnUiThread(() -> showMessage("Connection refused",
                    "The served certificate does not match this "
                    + "instance's pinned CA (" + error.getUrl()
                    + ")."));
        }

        /** The leaf must be SIGNED by a pinned CA (issuer check +
         *  signature verification — WebView only surfaces the leaf). */
        private boolean chainMatches(X509Certificate leaf)
                throws Exception {
            java.security.cert.CertificateFactory cf =
                    java.security.cert.CertificateFactory
                            .getInstance("X.509");
            for (String pem : inst.tls.caPem) {
                for (java.security.cert.Certificate c
                        : cf.generateCertificates(
                                new java.io.ByteArrayInputStream(
                                        pem.getBytes(
                                            StandardCharsets
                                                .UTF_8)))) {
                    X509Certificate ca = (X509Certificate) c;
                    try {
                        leaf.verify(ca.getPublicKey());
                        return true;
                    } catch (Exception notThisCa) {
                        if (MessageDigest.isEqual(
                                sha256(leaf.getEncoded()),
                                sha256(ca.getEncoded()))) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private byte[] sha256(byte[] data) throws Exception {
            return MessageDigest.getInstance("SHA-256").digest(data);
        }
    }

    // ---- vr: delegate to Wolvic ---------------------------------

    /** Quest 2 / Vive: Wolvic is REQUIRED (Dustin's call) — it is
     *  the headset browser with real WebXR, which the suite's XR
     *  pages need. The shell stays the registrar/prober. */
    private void openInWolvic(RegisteredInstance inst) {
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse(inst.config.webUrl));
        intent.setPackage("com.igalia.wolvic");
        try {
            startActivity(intent);
            showMessage(title(inst),
                    "Opened in Wolvic. This shell stays the "
                    + "registrar — reopen it to switch instances "
                    + "or re-probe.");
        } catch (ActivityNotFoundException e) {
            showMessage("Wolvic required",
                    "The VR shell renders through Wolvic (the "
                    + "WebXR browser) and it is not installed. "
                    + "Install Wolvic from the headset's store, "
                    + "then reopen Polari.");
        }
    }

    // ---- registration sources -----------------------------------

    private Optional<ShellConfig> bakedConfig() {
        try (InputStream in = getAssets()
                .open("polari-shell.json")) {
            // readAllBytes is API 33; loop stays 26-safe.
            java.io.ByteArrayOutputStream out =
                    new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return ConfigLoader.parse(new String(
                    out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception absent) {
            return Optional.empty();
        }
    }

    private void handleDeepLink(String uri) {
        DeepLink.parse(uri)
                .filter(p -> p.kind() == DeepLink.Kind.REGISTER)
                .ifPresent(p -> {
                    if (!p.payloadJson().isBlank()) {
                        ConfigLoader.parse(p.payloadJson())
                                .ifPresent(cfg -> registry.merge(
                                        cfg, "deeplink"));
                        return;
                    }
                    if (p.api().isBlank() || p.token().isBlank()) {
                        return;
                    }
                    StringBuilder err = new StringBuilder();
                    // TOFU: fetch, then refuse unless the delivered
                    // CA hashes to the link's pin (core contract).
                    ShellConfig doc = AndroidHttp.redeem(
                            p.api().replaceAll("/$", "")
                                    + "/api/appstore/enroll/redeem",
                            p.token(), Build.MODEL, null, err);
                    if (doc == null) {
                        showMessage("Registration failed",
                                err + ("local".equals(p.scope())
                                ? "\n\nThis instance lives on a "
                                  + p.networkKind() + " network ('"
                                  + p.networkName() + "') — you "
                                  + "may need to be on it to "
                                  + "register."
                                : ""));
                        return;
                    }
                    boolean pinHolds = p.caSha256().isBlank()
                            || doc.instances.stream().allMatch(i ->
                                    i.tls.caPem.isEmpty()
                                    || i.tls.caPem.stream().anyMatch(
                                        pem -> org.polari.shell.core
                                            .tls.InstanceTrust
                                            .pemSha256(pem)
                                            .equalsIgnoreCase(
                                                p.caSha256())));
                    if (!pinHolds) {
                        showMessage("Registration refused",
                                "The delivered CA does not match "
                                + "the link's fingerprint pin.");
                        return;
                    }
                    registry.merge(doc, "deeplink");
                    doc.instances.forEach(i -> registry.get(i.id)
                            .ifPresent(r -> r.enrolled = true));
                });
    }

    // ---- tiny UI helpers ----------------------------------------

    private String title(RegisteredInstance inst) {
        return inst.config.displayName.isBlank()
                ? inst.config.id : inst.config.displayName;
    }

    private LinearLayout messageBox(String heading, String body) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 96, 48, 48);
        box.setBackgroundColor(Color.WHITE);
        TextView h = new TextView(this);
        h.setTextSize(20);
        h.setText(heading);
        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, 24, 0, 24);
        status.setText(body);
        box.addView(h);
        box.addView(status);
        setContentView(box);
        return box;
    }

    private void showMessage(String heading, String body) {
        messageBox(heading, body);
    }
}
