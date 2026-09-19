package org.polari.shell.core.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SECURITY BOUNDARY of the pipeline shell (ci-11b) — the exact
 * role {@code HostInstall} plays for the store, generalised to a
 * named verb with parameters.
 *
 * <p>Two allowlists have to agree before anything runs:
 * <ol>
 *   <li>the SHIPPED one — `shell-verbs.json` names the verb, its
 *       argv template, whether it is privileged, and a regex per
 *       parameter;</li>
 *   <li>this HARD-CODED one — {@link #ARGV0_ALLOWED}: the resolved
 *       argv[0] must be one of four programs. A tampered JSON cannot
 *       introduce a fifth, so replacing the file buys an attacker
 *       nothing but a rearrangement of `pol`/`apt-get`/`usermod`/
 *       `nmcli` invocations — and each of those still has to survive
 *       the per-parameter regexes.</li>
 * </ol>
 *
 * <p>Pure + no platform imports, so it is unit-tested in :core; the
 * process execution lives in :desktop.
 */
public final class VerbCommand {

    static final Pattern PLACEHOLDER =
            Pattern.compile("\\{([a-z][a-z0-9_]{0,31})\\}");

    /** The SECOND allowlist — hard-coded in this binary, never read
     *  from a file. `pol` is the pipeline CLI; the other three are
     *  the only system commands a setup step may need. */
    public static final List<String> ARGV0_ALLOWED =
            List.of("pol", "apt-get", "usermod", "nmcli");

    /** apt can legitimately take minutes; everything else is a
     *  read or a one-shot CLI verb and must not hang the window. */
    public static final long TIMEOUT_APT_SECONDS = 600;
    public static final long TIMEOUT_SECONDS = 120;

    private VerbCommand() {}

    public static long timeoutSeconds(VerbSpec verb) {
        return "apt-get".equals(verb.argv.get(0))
                ? TIMEOUT_APT_SECONDS : TIMEOUT_SECONDS;
    }

    /**
     * Resolve a verb's argv template with validated parameters.
     * This is what actually runs (unprivileged), and what the
     * privileged wrapper independently recomputes as root.
     *
     * @throws IllegalArgumentException on ANY of: unknown parameter,
     *         missing parameter, a value failing its regex, a value
     *         carrying a control character, an unresolved
     *         placeholder, or an argv[0] outside
     *         {@link #ARGV0_ALLOWED}
     */
    public static List<String> resolve(VerbSpec verb,
                                       Map<String, String> params) {
        Map<String, String> given = params == null
                ? Map.of() : params;
        for (String name : given.keySet()) {
            if (!verb.params.containsKey(name)) {
                throw new IllegalArgumentException(
                        "refused: verb " + verb.id
                        + " has no parameter '" + name + "'");
            }
        }
        Map<String, String> checked = new LinkedHashMap<>();
        for (String need : verb.placeholders()) {
            String value = given.get(need);
            if (value == null) {
                throw new IllegalArgumentException(
                        "refused: verb " + verb.id
                        + " needs parameter '" + need + "'");
            }
            if (hasControlChar(value)) {
                throw new IllegalArgumentException(
                        "refused: parameter '" + need
                        + "' carries a control character");
            }
            Pattern p = verb.params.get(need);
            if (!p.matcher(value).matches()) {
                throw new IllegalArgumentException(
                        "refused: parameter '" + need
                        + "' does not match " + p.pattern());
            }
            checked.put(need, value);
        }
        List<String> out = new ArrayList<>(verb.argv.size());
        for (String tpl : verb.argv) {
            out.add(substitute(tpl, checked));
        }
        String argv0 = out.get(0);
        if (!ARGV0_ALLOWED.contains(argv0)) {
            throw new IllegalArgumentException(
                    "refused: argv[0] '" + argv0 + "' is not one of "
                    + ARGV0_ALLOWED
                    + " (the allowlist in the shell, not the file)");
        }
        return List.copyOf(out);
    }

    /** An UNPRIVILEGED run: the verb must not be marked privileged
     *  — `pipeline.run` may never become a back door into pkexec,
     *  and a privileged verb run bare would fail confusingly. */
    public static List<String> unprivileged(
            VerbCatalog catalog, String id,
            Map<String, String> params) {
        VerbSpec v = require(catalog, id);
        if (v.privileged) {
            throw new IllegalArgumentException(
                    "refused: verb '" + id + "' is privileged — it "
                    + "must go through pipeline.privileged");
        }
        return resolve(v, params);
    }

    /**
     * A PRIVILEGED run. The returned argv is
     * `pkexec &lt;wrapper&gt; &lt;verb-id&gt; name=value…` — pkexec
     * never sees the real command, because the polkit action pins
     * its exec.path at the wrapper. The real argv is still resolved
     * here first, so a verb this binary would refuse is refused
     * BEFORE any password is asked for.
     */
    public static List<String> privileged(
            VerbCatalog catalog, String id,
            Map<String, String> params, String wrapperPath) {
        VerbSpec v = require(catalog, id);
        if (!v.privileged) {
            throw new IllegalArgumentException(
                    "refused: verb '" + id + "' is not marked "
                    + "privileged — run it with pipeline.run");
        }
        resolve(v, params);   // validate before prompting
        List<String> out = new ArrayList<>();
        out.add("pkexec");
        out.add(wrapperPath);
        out.add(v.id);
        for (String need : v.placeholders()) {
            out.add(need + "=" + params.get(need));
        }
        return List.copyOf(out);
    }

    private static VerbSpec require(VerbCatalog catalog, String id) {
        return catalog.find(id).orElseThrow(() ->
                new IllegalArgumentException(
                        "refused: unknown verb '" + id + "'"));
    }

    private static String substitute(String tpl,
                                     Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(tpl);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            if (v == null) {
                throw new IllegalArgumentException(
                        "refused: unresolved placeholder {"
                        + m.group(1) + "}");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean hasControlChar(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }
}
