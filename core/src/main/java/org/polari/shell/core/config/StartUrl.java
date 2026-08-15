package org.polari.shell.core.config;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * sep-1: the ONE place the shell turns a registration's app block
 * into the URL it opens. The URL is the app-identity channel
 * (separation plan decision 1): a scope=app registration appends
 * `?shellApp=<appName>` — the SPA's single-app clamp (sep-0) does
 * the rest, in any browser, no bridge needed. Shared by every
 * platform half (desktop today; android is the same call) so the
 * rule cannot drift per platform.
 */
public final class StartUrl {

    private StartUrl() {}

    /** The URL to open for an instance + app block. Blank
     *  startRoute on a scope=app registration defaults to the app
     *  home (/app/<name>) — the same place the SPA's clamp guard
     *  sends foreign routes. */
    public static String of(String webUrl, ShellConfig.App app) {
        String base = webUrl == null ? "" : webUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String route = app == null || app.startRoute == null
                ? "" : app.startRoute.trim();
        boolean clamped = app != null
                && "app".equals(app.scope)
                && app.appName != null && !app.appName.isBlank();
        if (clamped) {
            String name = app.appName.trim();
            if (route.isBlank()) {
                route = "/app/" + name;
            }
            return base + slashed(route)
                    + (route.contains("?") ? "&" : "?")
                    + "shellApp="
                    + URLEncoder.encode(name, StandardCharsets.UTF_8);
        }
        return route.isBlank() ? base : base + slashed(route);
    }

    private static String slashed(String route) {
        return route.startsWith("/") ? route : "/" + route;
    }
}
