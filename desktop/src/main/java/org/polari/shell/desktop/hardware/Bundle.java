package org.polari.shell.desktop.hardware;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * An opened demo bundle: a directory holding firmware.hex,
 * polari-install.json, polari-hw-bridge.jar, bridge.properties,
 * app-definition.json and a README. Missing parts are tolerated here;
 * each step says what it lacks.
 */
public final class Bundle {

    public static final List<String> DEFAULT_STEPS = List.of(
            "Open bundle", "Gate", "Detect board", "Flash",
            "Host bridge", "Light");

    public final Path dir;

    private Bundle(Path dir) {
        this.dir = dir;
    }

    /** The bundle root: dir itself, or the one folder under it that
     *  holds firmware.hex (tar.gz bundles usually nest one level). */
    public static Bundle at(Path dir) throws IOException {
        if (Files.isRegularFile(dir.resolve("firmware.hex"))) {
            return new Bundle(dir);
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path c : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(c.resolve("firmware.hex"))) {
                    return new Bundle(c);
                }
            }
        }
        return new Bundle(dir);
    }

    public Path hex() {
        return dir.resolve("firmware.hex");
    }

    public Path jar() {
        return dir.resolve("polari-hw-bridge.jar");
    }

    public Path properties() {
        return dir.resolve("bridge.properties");
    }

    public boolean hasFirmware() {
        return Files.isRegularFile(hex());
    }

    public JsonObject installManifest() {
        return readObject(dir.resolve("polari-install.json"));
    }

    public String propertiesText() {
        try {
            return Files.readString(properties(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** First lines of the README (advice on screen, never a gate). */
    public String readmeHead(int lines) {
        for (String n : new String[] {"README.md", "README.txt",
                "README"}) {
            Path p = dir.resolve(n);
            if (Files.isRegularFile(p)) {
                try (BufferedReader r = Files.newBufferedReader(p,
                        StandardCharsets.UTF_8)) {
                    StringBuilder sb = new StringBuilder();
                    String l;
                    int c = 0;
                    while (c < lines && (l = r.readLine()) != null) {
                        if (!l.isBlank()) {
                            sb.append(l).append('\n');
                            c++;
                        }
                    }
                    return sb.toString();
                } catch (IOException e) {
                    return "";
                }
            }
        }
        return "";
    }

    /** One step as the app definition names it. */
    public record Step(String name, String purpose) {}

    /** The app's steps, in order: solutions[name == uno-light-app]
     *  (a SolutionDefinition row: its definition's stateInstances,
     *  each with boundObjectFieldValues.displayName/purpose), else a
     *  top-level states/steps list, else the built-in six. */
    public List<Step> steps() {
        return stepsFrom(readObject(dir.resolve("app-definition.json")));
    }

    public List<String> stepNames() {
        return steps().stream().map(Step::name).toList();
    }

    static List<String> stepNamesFrom(JsonObject def) {
        return stepsFrom(def).stream().map(Step::name).toList();
    }

    static List<Step> stepsFrom(JsonObject def) {
        List<Step> r = def == null ? List.of() : fromSolutions(def);
        if (r.isEmpty() && def != null) {
            r = fromStateList(def);
        }
        if (r.isEmpty()) {
            r = DEFAULT_STEPS.stream()
                    .map(n -> new Step(n, "")).toList();
        }
        return r;
    }

    private static List<Step> fromSolutions(JsonObject def) {
        if (!def.has("solutions") || !def.get("solutions").isJsonArray()) {
            return List.of();
        }
        for (JsonElement e : def.getAsJsonArray("solutions")) {
            if (!e.isJsonObject()
                    || !"uno-light-app".equals(str(e.getAsJsonObject(),
                            "name"))) {
                continue;
            }
            JsonObject row = e.getAsJsonObject();
            JsonObject d = row;
            if (row.has("definition")) {
                JsonElement x = row.get("definition");
                try {
                    if (x.isJsonPrimitive()) {
                        x = JsonParser.parseString(x.getAsString());
                    }
                    if (x.isJsonObject()) {
                        d = x.getAsJsonObject();
                    }
                } catch (Exception ex) {
                    d = row;
                }
            }
            for (String k : new String[] {"stateInstances", "states"}) {
                if (d.has(k) && d.get(k).isJsonArray()) {
                    List<Step> r = stepList(d.getAsJsonArray(k));
                    if (!r.isEmpty()) {
                        return r;
                    }
                }
            }
        }
        return List.of();
    }

    private static List<Step> fromStateList(JsonObject def) {
        for (String key : new String[] {"states", "steps"}) {
            if (def.has(key) && def.get(key).isJsonArray()) {
                List<Step> r = stepList(def.getAsJsonArray(key));
                if (r.size() == DEFAULT_STEPS.size()) {
                    return r;
                }
            }
        }
        return List.of();
    }

    private static List<Step> stepList(JsonArray a) {
        List<Step> names = new ArrayList<>();
        for (JsonElement e : a) {
            String n = nameOf(e);
            if (n.isEmpty()) {
                return List.of();
            }
            names.add(new Step(n, purposeOf(e)));
        }
        return names;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive()
                ? o.get(k).getAsString() : "";
    }

    private static JsonObject fields(JsonElement e) {
        if (e.isJsonObject() && e.getAsJsonObject()
                .has("boundObjectFieldValues")
                && e.getAsJsonObject().get("boundObjectFieldValues")
                        .isJsonObject()) {
            return e.getAsJsonObject()
                    .getAsJsonObject("boundObjectFieldValues");
        }
        return new JsonObject();
    }

    private static String purposeOf(JsonElement e) {
        if (!e.isJsonObject()) {
            return "";
        }
        String p = str(fields(e), "purpose");
        return p.isEmpty() ? str(e.getAsJsonObject(), "purpose") : p;
    }

    private static String nameOf(JsonElement e) {
        if (e.isJsonPrimitive()) {
            return e.getAsString();
        }
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            String d = str(fields(e), "displayName");
            if (!d.isBlank()) {
                return d.replaceFirst("^\\d+\\.\\s*", "");
            }
            for (String k : new String[] {"displayName", "title",
                    "label", "name", "stateName", "id"}) {
                if (!str(o, k).isBlank()) {
                    return str(o, k).replaceFirst("^\\d+\\.\\s*", "");
                }
            }
        }
        return "";
    }

    private static JsonObject readObject(Path p) {
        try {
            JsonElement e = JsonParser.parseString(
                    Files.readString(p, StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception ex) {
            return null;
        }
    }
}
