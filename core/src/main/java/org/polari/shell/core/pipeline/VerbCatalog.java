package org.polari.shell.core.pipeline;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The verb ALLOWLIST, loaded from `shell-verbs.json` (ci-11b).
 *
 * <p>The file is the ONE thing that crosses from the bash half of
 * the pipeline into this shell: it is shipped in the
 * `polari-pipeline` deb at
 * `/usr/share/polari-pipeline/shell-verbs.json`, read once at start,
 * and re-read independently by the privileged wrapper before
 * anything runs as root.
 *
 * <p>Loading it is NOT trusting it. A verb whose argv[0] is outside
 * {@link VerbCommand#ARGV0_ALLOWED} is refused at execution time by
 * a hard-coded second allowlist in this binary, so a tampered JSON
 * buys an attacker nothing.
 */
public final class VerbCatalog {

    public static final String PROTOCOL = "polari-pipeline-shell/1";

    /** Where the deb installs the file. Overridable ONLY by a JVM
     *  system property, so a hostile page/env cannot repoint it. */
    public static final String DEFAULT_PATH =
            "/usr/share/polari-pipeline/shell-verbs.json";

    private final Map<String, VerbSpec> verbs;

    private VerbCatalog(Map<String, VerbSpec> verbs) {
        // LinkedHashMap, not Map.copyOf: the file's DECLARATION
        // order is what a UI lists, and Map.copyOf randomises it.
        this.verbs = java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(verbs));
    }

    public static Path defaultPath() {
        return Paths.get(System.getProperty(
                "polari.pipeline.verbs", DEFAULT_PATH));
    }

    public static VerbCatalog load(Path file) throws Exception {
        return parse(new String(Files.readAllBytes(file),
                StandardCharsets.UTF_8));
    }

    /** Empty catalog — the honest state when no file is installed. */
    public static VerbCatalog empty() {
        return new VerbCatalog(Map.of());
    }

    /**
     * @throws IllegalArgumentException on anything that is not a
     *         well-formed v1 document (wrong protocol, bad verb id,
     *         bad param name, uncompilable regex, empty argv…)
     */
    public static VerbCatalog parse(String json) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "refused: shell-verbs.json is not a JSON object",
                    e);
        }
        String proto = str(root, "protocol");
        if (!PROTOCOL.equals(proto)) {
            throw new IllegalArgumentException(
                    "refused: protocol is '" + proto + "', expected "
                    + PROTOCOL);
        }
        JsonElement vs = root.get("verbs");
        if (vs == null || !vs.isJsonObject()) {
            throw new IllegalArgumentException(
                    "refused: no verbs object");
        }
        Map<String, VerbSpec> out = new LinkedHashMap<>();
        for (var e : vs.getAsJsonObject().entrySet()) {
            VerbSpec v = verb(e.getKey(), e.getValue());
            out.put(v.id, v);
        }
        return new VerbCatalog(out);
    }

    private static VerbSpec verb(String id, JsonElement el) {
        if (!VerbSpec.VERB_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "refused: not a verb id: " + id);
        }
        if (el == null || !el.isJsonObject()) {
            throw new IllegalArgumentException(
                    "refused: verb " + id + " is not an object");
        }
        JsonObject o = el.getAsJsonObject();
        JsonElement argvEl = o.get("argv");
        if (argvEl == null || !argvEl.isJsonArray()
                || argvEl.getAsJsonArray().isEmpty()) {
            throw new IllegalArgumentException(
                    "refused: verb " + id + " has no argv");
        }
        List<String> argv = new ArrayList<>();
        argvEl.getAsJsonArray().forEach(a -> {
            if (!a.isJsonPrimitive()) {
                throw new IllegalArgumentException(
                        "refused: verb " + id
                        + " argv holds a non-string");
            }
            argv.add(a.getAsString());
        });
        boolean priv = o.has("privileged")
                && o.get("privileged").isJsonPrimitive()
                && o.get("privileged").getAsBoolean();
        String stdin = o.has("stdin") && !o.get("stdin").isJsonNull()
                ? o.get("stdin").getAsString() : null;
        if (stdin != null && !stdin.isEmpty()
                && !"secret".equals(stdin)) {
            throw new IllegalArgumentException(
                    "refused: verb " + id + " stdin must be "
                    + "'secret' or null, got " + stdin);
        }
        Map<String, Pattern> params = new LinkedHashMap<>();
        if (o.has("params") && o.get("params").isJsonObject()) {
            for (var p : o.getAsJsonObject("params").entrySet()) {
                if (!VerbSpec.PARAM_NAME.matcher(p.getKey())
                        .matches()) {
                    throw new IllegalArgumentException(
                            "refused: verb " + id
                            + " parameter name: " + p.getKey());
                }
                params.put(p.getKey(),
                        compile(id, p.getKey(),
                                p.getValue().getAsString()));
            }
        }
        VerbSpec spec = new VerbSpec(id, argv, priv, stdin, params,
                str(o, "label"));
        // every placeholder must be a DECLARED parameter; otherwise
        // the template could never be resolved and an unvalidated
        // literal would survive into argv
        for (String need : spec.placeholders()) {
            if (!params.containsKey(need)) {
                throw new IllegalArgumentException(
                        "refused: verb " + id + " uses {" + need
                        + "} but declares no such parameter");
            }
        }
        return spec;
    }

    private static Pattern compile(String id, String name,
                                   String regex) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "refused: verb " + id + " parameter " + name
                    + " has an uncompilable regex", e);
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive()
                ? o.get(key).getAsString() : "";
    }

    public Optional<VerbSpec> find(String id) {
        return Optional.ofNullable(verbs.get(id));
    }

    /** Verb ids, for `pipeline.available`. */
    public List<String> ids() {
        return List.copyOf(verbs.keySet());
    }

    public boolean isEmpty() {
        return verbs.isEmpty();
    }
}
