package org.polari.shell.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.polari.shell.core.host.HostInstall;

class HostInstallTest {

    @Test
    void acceptsCatalogNames() {
        assertTrue(HostInstall.validName("polari"));
        assertTrue(HostInstall.validName("odoo"));
        assertTrue(HostInstall.validName("whoami"));
        assertTrue(HostInstall.validName("my-app-2"));
    }

    @Test
    void rejectsInjectionAttempts() {
        // the whole point: a hostile page must not reach pkexec
        for (String bad : new String[]{
                "; rm -rf /", "a b", "app;reboot", "$(whoami)",
                "../x", "app/../y", "APP", "-flag", "", null,
                "a".repeat(65)}) {
            assertFalse(HostInstall.validName(bad),
                    "must reject: " + bad);
        }
    }

    @Test
    void commandIsFixedArgvWithNameIsolated() {
        List<String> cmd = HostInstall.installCommand("odoo");
        assertEquals(List.of("pkexec", "isle", "store", "install",
                "odoo", "--yes"), cmd);
    }

    @Test
    void badNameThrowsNotBuilds() {
        assertThrows(IllegalArgumentException.class,
                () -> HostInstall.installCommand("; reboot"));
    }

    @Test
    void installedVersionIsFixedReadOnlyArgv() {
        List<String> cmd =
                HostInstall.installedVersionCommand("polari");
        assertEquals(List.of("dpkg-query", "-W", "-f",
                "${Version}", "isle-app-polari"), cmd);
        // never privileged
        assertFalse(cmd.contains("pkexec"));
    }

    @Test
    void installedVersionIsKindAware() {
        // sep-2: the deb builder names packages <kind>-app-<name>;
        // the query follows, isle stays the one-arg default.
        assertEquals(List.of("dpkg-query", "-W", "-f",
                "${Version}", "polari-app-wax-print-shop"),
                HostInstall.installedVersionCommand(
                        "wax-print-shop", "polari"));
        assertTrue(HostInstall.validKind("isle"));
        assertTrue(HostInstall.validKind("polari"));
        assertFalse(HostInstall.validKind("evil"));
        assertThrows(IllegalArgumentException.class,
                () -> HostInstall.installedVersionCommand(
                        "polari", "web; reboot"));
    }

    @Test
    void uninstallIsFixedArgvWithNameIsolated() {
        // unin-4: the Uninstall button = this one engine verb, thin
        assertEquals(List.of("pkexec", "isle", "store", "uninstall",
                "odoo", "--yes"),
                HostInstall.uninstallCommand("odoo", false));
        assertEquals(List.of("pkexec", "isle", "store", "uninstall",
                "odoo", "--yes", "--purge"),
                HostInstall.uninstallCommand("odoo", true));
        assertThrows(IllegalArgumentException.class,
                () -> HostInstall.uninstallCommand("; reboot",
                        true));
    }

    @Test
    void meshDeployedIsFixedReadOnlyArgv() {
        // unin-4: deployed-here probe — unprivileged, traversal-proof
        assertEquals(List.of("test", "-d",
                "/etc/isle-mesh/apps/whoami"),
                HostInstall.meshDeployedCommand("whoami"));
        assertFalse(HostInstall.meshDeployedCommand("whoami")
                .contains("pkexec"));
        assertThrows(IllegalArgumentException.class,
                () -> HostInstall.meshDeployedCommand("../etc"));
    }

    @Test
    void installedVersionRejectsBadNames() {
        assertThrows(IllegalArgumentException.class,
                () -> HostInstall.installedVersionCommand(
                        "$(id)"));
    }
}
