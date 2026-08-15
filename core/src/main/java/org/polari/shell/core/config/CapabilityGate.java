package org.polari.shell.core.config;

import java.util.List;

/**
 * sep-5 (decision 8): the declaration gate. A registration's
 * `capabilities` list carries REFERENCES to AppEdgeBehavior rows
 * (served by GET /api/appstore/behaviors) — the definitions live in
 * the backend's object model, never in the shell. The shell's rare
 * NATIVE halves (Gradle capability modules, ServiceLoader) must ask
 * this gate before doing anything at the edge: undeclared = refused,
 * and the default (no capabilities) is deny-all. The §5l rule rides
 * along: the privileged helper holds the privilege, the shell only
 * ever holds the declaration.
 */
public final class CapabilityGate {

    private CapabilityGate() {}

    /** True iff the registration DECLARES the named behavior. */
    public static boolean allowed(ShellConfig.App app, String name) {
        if (app == null || name == null || name.isBlank()) {
            return false;
        }
        List<String> declared = app.capabilities;
        return declared != null && declared.contains(name);
    }

    /** The declared references, never null — what the shell may ask
     *  the backend to resolve (/api/appstore/behaviors?names=...). */
    public static List<String> declared(ShellConfig.App app) {
        return app == null || app.capabilities == null
                ? List.of() : List.copyOf(app.capabilities);
    }
}
