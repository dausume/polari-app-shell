package org.polari.shell.desktop.hardware;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import javax.net.ssl.SSLContext;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Step 6: the light, through Polari only. GET /LightState reads the
 * row; the toggle is the CRUDE PUT (multipart polariId + updateData)
 * on that row. There is no serial write here: the command goes up to
 * Polari and comes back down the bridge.
 */
public final class LightClient {

    public record State(String id, boolean ledOn, long frames,
                        long toggles, String source, String status) {
        public String sentence() {
            return "Light is " + (ledOn ? "ON" : "off") + " - frames "
                    + frames + ", toggles " + toggles + ", source "
                    + (source.isEmpty() ? "(none yet)" : source)
                    + ", status " + status + ".";
        }
    }

    private final String base;
    private final HttpClient http;

    public LightClient(String apiBase, SSLContext trust) {
        this.base = apiBase.replaceAll("/+$", "");
        this.http = BundleSource.client(trust);
    }

    /** Parse the GET /LightState body (a list of {class, data[]}). */
    public static State parse(String body) {
        JsonElement root = JsonParser.parseString(body);
        JsonArray groups = root.isJsonArray() ? root.getAsJsonArray()
                : new JsonArray();
        JsonObject pick = null;
        for (JsonElement g : groups) {
            JsonObject go = g.getAsJsonObject();
            if (!go.has("data")) {
                continue;
            }
            for (JsonElement r : go.getAsJsonArray("data")) {
                JsonObject ro = r.getAsJsonObject();
                if (pick == null || "uno-light".equals(
                        s(ro, "name"))) {
                    pick = ro;
                }
            }
        }
        if (pick == null) {
            return null;
        }
        return new State(s(pick, "id"),
                pick.has("led_on") && pick.get("led_on")
                        .getAsBoolean(),
                l(pick, "seq"), l(pick, "toggles"),
                s(pick, "source"),
                s(pick, "status").isEmpty() ? "unknown"
                        : s(pick, "status"));
    }

    public State read() throws Exception {
        HttpResponse<String> r = http.send(req("/LightState")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IllegalStateException("Polari answered "
                    + r.statusCode() + " for LightState.");
        }
        State s = parse(r.body());
        if (s == null) {
            throw new IllegalStateException("Polari has no LightState "
                    + "row yet - the bridge has not reported a frame.");
        }
        return s;
    }

    /** The multipart body of the CRUDE PUT. */
    static String putBody(String boundary, String id, boolean ledOn) {
        String nl = "\r\n";
        return "--" + boundary + nl
                + "Content-Disposition: form-data; name=\"polariId\""
                + nl + nl + id + nl
                + "--" + boundary + nl
                + "Content-Disposition: form-data; "
                + "name=\"updateData\"" + nl + nl
                + "{\"led_on\": " + ledOn + "}" + nl
                + "--" + boundary + "--" + nl;
    }

    /** Ask for the opposite of the row's current led_on. */
    public State toggle() throws Exception {
        State now = read();
        String boundary = "polari" + UUID.randomUUID()
                .toString().replace("-", "");
        HttpResponse<String> r = http.send(req("/LightState")
                .header("Content-Type",
                        "multipart/form-data; boundary=" + boundary)
                .PUT(HttpRequest.BodyPublishers.ofString(
                        putBody(boundary, now.id(), !now.ledOn()),
                        StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) {
            throw new IllegalStateException("Polari refused the "
                    + "toggle (" + r.statusCode() + "): "
                    + r.body().strip());
        }
        return now;
    }

    private HttpRequest.Builder req(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                URI.create(base + path))
                .timeout(Duration.ofSeconds(15));
        String tok = System.getenv("POLARI_TOKEN");
        if (tok != null && !tok.isBlank()) {
            b.header("Authorization", "Bearer " + tok);
        }
        return b;
    }

    private static String s(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive()
                ? o.get(k).getAsString() : "";
    }

    private static long l(JsonObject o, String k) {
        try {
            return o.has(k) ? o.get(k).getAsLong() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
