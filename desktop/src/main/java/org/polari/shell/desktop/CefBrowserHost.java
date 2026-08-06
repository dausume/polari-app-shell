package org.polari.shell.desktop;

import java.awt.Component;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefMessageRouter;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.cef.callback.CefQueryCallback;

import org.polari.shell.core.bridge.BridgeRouter;
import org.polari.shell.core.config.InstanceConfig;

/**
 * JCEF embedding (plan B1). Windowed (non-OSR) mode — the
 * best-tested path; the browser component is heavyweight AWT, which
 * is exactly why the host window is a Swing JFrame. Per-instance
 * cache_path isolates each instance's Keycloak SSO cookies, so
 * instance switching is a browser swap and Strategy-A auto-login is
 * just the persisted profile.
 */
final class CefBrowserHost {

    private final CefApp app;
    private final CefClient client;
    private CefBrowser browser;

    CefBrowserHost(InstanceConfig inst, BridgeRouter bridge)
            throws Exception {
        CefAppBuilder builder = new CefAppBuilder();
        builder.getCefSettings().windowless_rendering_enabled = false;
        Path share = Paths.get(
                System.getProperty("user.home"),
                ".local", "share", "polari-shell");
        builder.setInstallDir(
                share.resolve("jcef-natives").toFile());
        builder.getCefSettings().cache_path = share
                .resolve("profiles").resolve(inst.id).toString();
        // Strategy A depends on the SPA's silent-renew iframe seeing
        // Keycloak's SSO cookie as usable — we own the browser, so
        // third-party-cookie phaseout is disabled (risk R4: pinned
        // jcefmaven version freezes these switches).
        builder.addJcefArgs(
                "--disable-features=ThirdPartyCookiePhaseout,"
                + "BlockThirdPartyCookies,TrackingProtection3pcd");
        builder.setAppHandler(new MavenCefAppHandlerAdapter() {});
        app = builder.build();
        client = app.createClient();
        client.addRequestHandler(new CertTrustHandler(inst));
        CefMessageRouter router = CefMessageRouter.create();
        router.addHandler(new CefMessageRouterHandlerAdapter() {
            @Override
            public boolean onQuery(CefBrowser b,
                    org.cef.browser.CefFrame frame, long queryId,
                    String request, boolean persistent,
                    CefQueryCallback callback) {
                callback.success(bridge.dispatch(request));
                return true;
            }
        }, true);
        client.addMessageRouter(router);
    }

    Component open(String url) {
        browser = client.createBrowser(url, false, false);
        return browser.getUIComponent();
    }

    void load(String url) {
        if (browser != null) {
            browser.loadURL(url);
        }
    }

    void dispose() {
        if (browser != null) {
            browser.close(true);
        }
        client.dispose();
    }

    static void shutdownAll() {
        CefApp.getInstance().dispose();
    }
}
