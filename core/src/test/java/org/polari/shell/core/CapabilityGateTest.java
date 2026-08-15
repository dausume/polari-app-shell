package org.polari.shell.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.polari.shell.core.config.CapabilityGate;
import org.polari.shell.core.config.ConfigLoader;
import org.polari.shell.core.config.ShellConfig;

/**
 * sep-5: the declaration gate. Default deny-all; only what the
 * registration declares passes; the declared list round-trips the
 * wire (capabilities in the registration document).
 */
class CapabilityGateTest {

    @Test
    void defaultIsDenyAll() {
        ShellConfig.App app = new ShellConfig.App();
        assertFalse(CapabilityGate.allowed(app, "camera-capture"));
        assertFalse(CapabilityGate.allowed(null, "anything"));
        assertFalse(CapabilityGate.allowed(app, ""));
        assertEquals(List.of(), CapabilityGate.declared(app));
        assertEquals(List.of(), CapabilityGate.declared(null));
    }

    @Test
    void declaredPassesUndeclaredRefused() {
        ShellConfig.App app = new ShellConfig.App();
        app.capabilities = List.of("lora-radio-attach");
        assertTrue(CapabilityGate.allowed(app, "lora-radio-attach"));
        assertFalse(CapabilityGate.allowed(app, "camera-capture"));
        assertEquals(List.of("lora-radio-attach"),
                CapabilityGate.declared(app));
    }

    @Test
    void capabilitiesRideTheWireDocument() {
        String doc = """
            {"kind": "polari-shell-registration",
             "schemaVersion": 1,
             "app": {"name": "wax", "scope": "app",
                     "appName": "wax-print-shop",
                     "capabilities": ["camera-capture"]},
             "instances": [{"id": "prf-a",
                            "webUrl": "https://prf.test",
                            "apiUrl": "https://api.prf.test"}]}
            """;
        ShellConfig cfg = ConfigLoader.parse(doc).orElseThrow();
        assertTrue(CapabilityGate.allowed(cfg.app, "camera-capture"));
        assertFalse(CapabilityGate.allowed(cfg.app,
                "lora-radio-attach"));
    }
}
