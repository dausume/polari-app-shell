package org.polari.shell.core.host;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The SECURITY BOUNDARY for privileged installs (handoff §31): the
 * store web page can ask the native shell to install a NAMED
 * catalog app, and nothing else. This class turns an app name into
 * a FIXED, argument-array command — never a shell string — so a
 * hostile page cannot inject anything: the name is validated
 * against a strict allowlist pattern, and the command is always
 * `pkexec isle store install <name> --yes` (polkit prompts the
 * user for their password; the isle CLI does the real work).
 *
 * Pure + no platform imports, so it is unit-tested in :core. The
 * actual process execution lives in :desktop (HostProcess).
 */
public final class HostInstall {

    /** Catalog names are lowercase kebab: what `isle store` accepts
     *  and `isle app deploy` names a container/domain. Anything
     *  else is rejected outright — no spaces, slashes, ;, $, etc. */
    private static final Pattern NAME =
            Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    private HostInstall() {}

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /**
     * The argv for a privileged install. Fixed shape; the ONLY
     * variable is the validated name, passed as its own argv
     * element (never concatenated into a shell line).
     *
     * @throws IllegalArgumentException if the name is not allowlisted
     */
    public static List<String> installCommand(String name) {
        if (!validName(name)) {
            throw new IllegalArgumentException(
                    "refused: not an installable catalog name: "
                    + name);
        }
        return List.of("pkexec", "isle", "store", "install",
                name, "--yes");
    }

    /** Non-privileged status/list — no pkexec, safe to run freely. */
    public static List<String> statusCommand() {
        return List.of("isle", "store", "list");
    }
}
