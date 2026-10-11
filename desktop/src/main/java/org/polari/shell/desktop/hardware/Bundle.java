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

    /** The app's step names, from app-definition.json when it lists
     *  exactly the panel's six states (strings, or objects with a
     *  title/label/name/id); else the built-in list. */
    public List<String> stepNames() {
        JsonObject def = readObject(dir.resolve("app-definition.json"));
        return stepNamesFrom(def);
    }

    static List<String> stepNamesFrom(JsonObject def) {
        for (String key : new String[] {"states", "steps"}) {
            if (def != null && def.has(key)
                    && def.get(key).isJsonArray()) {
                JsonArray a = def.getAsJsonArray(key);
                List<String> names = new ArrayList<>();
                for (JsonElement e : a) {
                    String n = nameOf(e);
                    if (n.isEmpty()) {
                        names.clear();
                        break;
                    }
                    names.add(n);
                }
                if (names.size() == DEFAULT_STEPS.size()) {
                    return names;
                }
            }
        }
        return DEFAULT_STEPS;
    }

    private static String nameOf(JsonElement e) {
        if (e.isJsonPrimitive()) {
            return e.getAsString();
        }
        if (e.isJsonObject()) {
            for (String k : new String[] {"title", "label", "name",
                    "id"}) {
                JsonElement v = e.getAsJsonObject().get(k);
                if (v != null && v.isJsonPrimitive()
                        && !v.getAsString().isBlank()) {
                    return v.getAsString();
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
