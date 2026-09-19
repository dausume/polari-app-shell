package org.polari.shell.desktop.pipeline;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;

import org.polari.shell.core.pipeline.SecretScrub;
import org.polari.shell.core.pipeline.VerbCatalog;
import org.polari.shell.core.pipeline.VerbCommand;
import org.polari.shell.core.pipeline.VerbSpec;
import org.polari.shell.desktop.HostProcess;

/**
 * Executes ALLOWLISTED pipeline verbs (ci-11b) — the desktop half of
 * the boundary whose rules live in {@code core.pipeline}.
 *
 * <p>It contains NO pipeline logic: it does not know what a step
 * means, what `doctor` checks or what `preflight` refuses. It knows
 * only: look the id up in the catalog, let core build the argv, run
 * it, hand back exit code + output (+ the parsed JSON when stdout
 * happens to be JSON).
 */
public final class PipelineRunner {

    /** The ONE thing pkexec is allowed to run — the polkit action's
     *  exec.path. */
    public static final String WRAPPER =
            "/usr/lib/polari-pipeline/privileged-run";

    public static final class Outcome {
        public final boolean ok;
        public final int exitCode;
        public final String output;
        public final JsonElement json;
        public final String error;

        Outcome(boolean ok, int exitCode, String output,
                JsonElement json, String error) {
            this.ok = ok;
            this.exitCode = exitCode;
            this.output = output;
            this.json = json;
            this.error = error;
        }

        static Outcome refused(String why) {
            return new Outcome(false, -1, "", null, why);
        }
    }

    private final VerbCatalog catalog;
    private final String wrapper;
    private final SecretPrompt prompt;

    public PipelineRunner(VerbCatalog catalog, String wrapper,
                          SecretPrompt prompt) {
        this.catalog = catalog;
        this.wrapper = wrapper;
        this.prompt = prompt;
    }

    /** Load the shipped allowlist; an absent or malformed file is an
     *  EMPTY catalog (`pipeline.available` then says so) — never a
     *  crash, and never a fallback set of verbs invented here. */
    public static PipelineRunner discover(SecretPrompt prompt) {
        VerbCatalog c;
        try {
            c = VerbCatalog.load(VerbCatalog.defaultPath());
        } catch (Exception e) {
            System.err.println("[pipeline] no usable verb allowlist"
                    + " at " + VerbCatalog.defaultPath() + ": " + e);
            c = VerbCatalog.empty();
        }
        return new PipelineRunner(c, WRAPPER, prompt);
    }

    public VerbCatalog catalog() {
        return catalog;
    }

    /** UNPRIVILEGED: runs as the desktop user. Privileged verbs are
     *  refused here by core, not by a check written twice. */
    public Outcome run(String id, Map<String, String> params) {
        List<String> argv;
        VerbSpec spec;
        try {
            // core refuses first — it owns every "why not" message
            argv = VerbCommand.unprivileged(catalog, id, params);
            spec = catalog.find(id).orElseThrow();
        } catch (Exception e) {
            return Outcome.refused(message(e));
        }
        return exec(argv, VerbCommand.timeoutSeconds(spec), null);
    }

    /**
     * PRIVILEGED: `pkexec <wrapper> <verb> name=value…`. When the
     * verb declares a secret on stdin the shell prompts natively
     * here — the value goes to the child and to nothing else, and
     * the output comes back scrubbed of it.
     */
    public Outcome runPrivileged(String id,
                                 Map<String, String> params) {
        List<String> argv;
        VerbSpec spec;
        try {
            argv = VerbCommand.privileged(catalog, id, params,
                    wrapper);
            spec = catalog.find(id).orElseThrow();
        } catch (Exception e) {
            return Outcome.refused(message(e));
        }
        long timeout = VerbCommand.timeoutSeconds(spec);
        if (!spec.wantsSecretOnStdin()) {
            return exec(argv, timeout, null);
        }
        char[] secret = prompt.ask(
                spec.label.isBlank() ? spec.id : spec.label,
                "This value is stored by the pipeline as "
                + describe(spec, params) + ".");
        if (secret == null) {
            return Outcome.refused("cancelled: no value was given");
        }
        try {
            Outcome raw = exec(argv, timeout, secret);
            return new Outcome(raw.ok, raw.exitCode,
                    SecretScrub.scrub(raw.output, secret),
                    null, raw.error);
        } finally {
            SecretScrub.wipe(secret);
        }
    }

    private Outcome exec(List<String> argv, long timeout,
                         char[] stdin) {
        try {
            HostProcess.Result r =
                    HostProcess.run(argv, timeout, stdin);
            return new Outcome(r.exitCode == 0, r.exitCode,
                    r.output, parse(r.output), null);
        } catch (Exception e) {
            return new Outcome(false, -1, "", null, e.toString());
        }
    }

    /** stdout is JSON or it is not; nothing else is ever parsed out
     *  of it (the contract's "ONE JSON document" rule). */
    static JsonElement parse(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        try {
            JsonElement el = JsonParser.parseString(output.trim());
            return el.isJsonObject() || el.isJsonArray() ? el : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A human phrase for the dialog — parameter VALUES only, which
     *  are already regex-bound; never the secret. */
    private static String describe(VerbSpec spec,
                                   Map<String, String> params) {
        StringBuilder sb = new StringBuilder(spec.id);
        for (String p : spec.placeholders()) {
            sb.append(' ').append(p).append('=')
                    .append(params.get(p));
        }
        return sb.toString();
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank()
                ? "refused: unknown verb" : m;
    }
}
