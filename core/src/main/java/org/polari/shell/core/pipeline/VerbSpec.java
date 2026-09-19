package org.polari.shell.core.pipeline;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ONE entry of the pipeline shell's verb allowlist (ci-11b).
 *
 * <p>A verb is a NAMED, fixed command shape. The page (or the native
 * first-run panel) may name a verb id and supply parameter VALUES;
 * it can never supply an argv. Every value is matched against the
 * verb's own regex before it is substituted into the template.
 *
 * <p>Deliberately dumb: this class knows nothing about what a verb
 * MEANS (what `doctor` checks, what `preflight` refuses). The
 * pipeline's logic lives in bash on the other side of
 * `shell-verbs.json`; the Java half only renders and executes.
 */
public final class VerbSpec {

    /** Parameter NAMES are as tightly bound as the values. */
    static final Pattern PARAM_NAME =
            Pattern.compile("[a-z][a-z0-9_]{0,31}");
    /** Verb ids: lowercase kebab, like the CLI verbs they wrap. */
    static final Pattern VERB_ID =
            Pattern.compile("[a-z][a-z0-9-]{0,63}");

    public final String id;
    /** argv template; entries may be exactly one `{param}` or a
     *  literal containing `{param}` placeholders. */
    public final List<String> argv;
    public final boolean privileged;
    /** "secret" = the SHELL prompts natively and writes the value to
     *  the child's stdin; null/"" = nothing on stdin. */
    public final String stdin;
    public final Map<String, Pattern> params;
    public final String label;

    VerbSpec(String id, List<String> argv, boolean privileged,
             String stdin, Map<String, Pattern> params,
             String label) {
        this.id = id;
        this.argv = List.copyOf(argv);
        this.privileged = privileged;
        this.stdin = stdin;
        this.params = java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(params));
        this.label = label;
    }

    public boolean wantsSecretOnStdin() {
        return "secret".equals(stdin);
    }

    /** The parameter names this verb's template actually uses, in
     *  first-appearance order — what a UI must collect. */
    public List<String> placeholders() {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (String a : argv) {
            var m = VerbCommand.PLACEHOLDER.matcher(a);
            while (m.find()) {
                seen.put(m.group(1), Boolean.TRUE);
            }
        }
        return List.copyOf(seen.keySet());
    }

    @Override
    public String toString() {
        return "VerbSpec[" + id + (privileged ? " privileged" : "")
                + " argv=" + argv + "]";
    }
}
