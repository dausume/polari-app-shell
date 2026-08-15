package org.polari.shell.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.polari.shell.core.config.ConfigLoader;
import org.polari.shell.core.config.ShellConfig;
import org.polari.shell.core.config.StartUrl;

/**
 * sep-1: the URL is the app-identity channel. StartUrl is the ONE
 * place the rule lives (desktop + android both call it); these pin
 * the clamp query, the app-home default, startRoute handling, and
 * the scope/appName coherence gate in looksValid().
 */
class StartUrlTest {

    private static ShellConfig.App app(String scope, String appName,
                                       String startRoute) {
        ShellConfig.App a = new ShellConfig.App();
        a.scope = scope;
        a.appName = appName;
        a.startRoute = startRoute;
        return a;
    }

    @Test
    void instanceScopeOpensTheBareWebUrl() {
        assertEquals("https://prf.test",
                StartUrl.of("https://prf.test",
                        app("instance", "", "")));
        assertEquals("https://prf.test",
                StartUrl.of("https://prf.test/", null));
    }

    @Test
    void instanceScopeHonorsStartRoute() {
        assertEquals("https://prf.test/isle",
                StartUrl.of("https://prf.test",
                        app("instance", "", "/isle")));
        // missing leading slash is repaired, not propagated
        assertEquals("https://prf.test/isle",
                StartUrl.of("https://prf.test/",
                        app("instance", "", "isle")));
    }

    @Test
    void appScopeClampsViaShellAppQuery() {
        assertEquals(
                "https://prf.test/app/wax-print-shop"
                        + "?shellApp=wax-print-shop",
                StartUrl.of("https://prf.test",
                        app("app", "wax-print-shop", "")));
    }

    @Test
    void appScopeKeepsExplicitStartRoute() {
        assertEquals(
                "https://prf.test/casting?shellApp=wax-print-shop",
                StartUrl.of("https://prf.test/",
                        app("app", "wax-print-shop", "/casting")));
        // a route already carrying a query gets & not a second ?
        assertEquals(
                "https://prf.test/casting?tab=wizard"
                        + "&shellApp=wax-print-shop",
                StartUrl.of("https://prf.test",
                        app("app", "wax-print-shop",
                                "/casting?tab=wizard")));
    }

    @Test
    void appScopeWithoutAppNameStaysUnclamped() {
        // looksValid() refuses such documents; if one slips through,
        // the URL falls open rather than clamping to nothing.
        assertEquals("https://prf.test",
                StartUrl.of("https://prf.test", app("app", "", "")));
    }

    @Test
    void looksValidRequiresAppNameForAppScope() {
        String doc = """
            {"kind": "polari-shell-registration",
             "schemaVersion": 1,
             "app": {"name": "wax", "scope": "app",
                     "appName": "%s"},
             "instances": [{"id": "prf-a",
                            "webUrl": "https://prf.test",
                            "apiUrl": "https://api.prf.test"}]}
            """;
        assertTrue(ConfigLoader.parse(
                doc.formatted("wax-print-shop")).isPresent());
        assertFalse(ConfigLoader.parse(
                doc.formatted("")).isPresent());
    }
}
