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

    /** sep-2: launcher packages come in two kinds — the deb builder
     *  names them `<kind>-app-<name>`. Anything else is refused. */
    public static boolean validKind(String kind) {
        return "isle".equals(kind) || "polari".equals(kind);
    }

    /**
     * Non-privileged installed-version query for ONE app's native
     * launcher: `dpkg-query -W -f ${Version} <kind>-app-<name>`.
     * Read-only, fixed argv, allowlisted name AND kind — exit 0
     * with a version on stdout means "installed on this device".
     *
     * @throws IllegalArgumentException if name or kind is not
     *         allowlisted
     */
    public static List<String> installedVersionCommand(String name,
                                                       String kind) {
        if (!validName(name)) {
            throw new IllegalArgumentException(
                    "refused: not an installable catalog name: "
                    + name);
        }
        if (!validKind(kind)) {
            throw new IllegalArgumentException(
                    "refused: not a launcher kind: " + kind);
        }
        return List.of("dpkg-query", "-W", "-f", "${Version}",
                kind + "-app-" + name);
    }

    /** Isle-kind convenience — the store's historical default. */
    public static List<String> installedVersionCommand(String name) {
        return installedVersionCommand(name, "isle");
    }
}
