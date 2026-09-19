package org.polari.shell.desktop.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A TOLERANT reader of the `polari-pipeline-setup/1` document
 * (ci-11b) — the second thing that crosses the boundary from the
 * bash half.
 *
 * <p>It is a reader, not an interpreter: no field here means
 * anything to the shell beyond "render this". Every field is
 * optional, because the bash half owns the protocol and may grow it;
 * an unknown kind renders as read-only text rather than failing.
 */
final class SetupDoc {

    static final String PROTOCOL = "polari-pipeline-setup/1";

    static final class Check {
        String label = "";
        String state = "";      // ok | warn | fail | anything
        String detail = "";
        String fix = "";
    }

    /** A question OR an action — both are "a label the person can
     *  act on, and the verb that acts". Keeping one type keeps the
     *  panel from growing opinions about the difference. */
    static final class Act {
        String id = "";
        String label = "";
        String kind = "";       // choice|text|confirm|checklist|secret
        String value = "";
        List<String> options = new ArrayList<>();
        String verb = "";
        Map<String, String> params = new LinkedHashMap<>();
        boolean runnable() {
            return !verb.isBlank();
        }
    }

    static final class Step {
        String id = "";
        String title = "";
        String explain = "";
        String where = "";
        List<Check> checks = new ArrayList<>();
        List<Act> questions = new ArrayList<>();
        List<Act> actions = new ArrayList<>();
    }

    String protocol = "";
    List<Step> steps = new ArrayList<>();
    List<String> todo = new ArrayList<>();
    int complete;
    int total;
    boolean ready;
    String blocking = "";

    boolean looksValid() {
        return PROTOCOL.equals(protocol) && !steps.isEmpty();
    }

    static SetupDoc of(JsonElement el) {
        SetupDoc d = new SetupDoc();
        if (el == null || !el.isJsonObject()) {
            return d;
        }
        JsonObject o = el.getAsJsonObject();
        d.protocol = str(o, "protocol");
        for (JsonElement s : arr(o, "steps")) {
            if (s.isJsonObject()) {
                d.steps.add(step(s.getAsJsonObject()));
            }
        }
        for (JsonElement t : arr(o, "todo")) {
            d.todo.add(t.isJsonObject()
                    ? line(t.getAsJsonObject())
                    : t.getAsString());
        }
        JsonObject sum = o.has("summary") && o.get("summary")
                .isJsonObject() ? o.getAsJsonObject("summary") : null;
        if (sum != null) {
            d.complete = num(sum, "complete");
            d.total = num(sum, "total");
            d.ready = sum.has("ready")
                    && sum.get("ready").isJsonPrimitive()
                    && sum.get("ready").getAsBoolean();
            d.blocking = str(sum, "blocking");
        }
        return d;
    }

    private static Step step(JsonObject o) {
        Step s = new Step();
        s.id = firstOf(o, "id", "name", "step");
        s.title = firstOf(o, "title", "label", "name");
        s.explain = firstOf(o, "explain", "explanation", "text");
        s.where = str(o, "where");
        for (JsonElement c : arr(o, "checks")) {
            if (c.isJsonObject()) {
                JsonObject co = c.getAsJsonObject();
                Check chk = new Check();
                chk.label = firstOf(co, "label", "name", "id");
                chk.state = firstOf(co, "state", "verdict",
                        "status");
                chk.detail = firstOf(co, "detail", "value",
                        "message");
                chk.fix = firstOf(co, "fix", "how", "todo");
                s.checks.add(chk);
            }
        }
        arr(o, "questions").forEach(q -> {
            if (q.isJsonObject()) {
                s.questions.add(act(q.getAsJsonObject()));
            }
        });
        arr(o, "actions").forEach(a -> {
            if (a.isJsonObject()) {
                s.actions.add(act(a.getAsJsonObject()));
            }
        });
        return s;
    }

    private static Act act(JsonObject o) {
        Act a = new Act();
        a.id = firstOf(o, "id", "name");
        a.label = firstOf(o, "label", "title", "id");
        a.kind = firstOf(o, "kind", "type");
        a.value = firstOf(o, "value", "current", "default");
        for (JsonElement op : arr(o, "options")) {
            if (op.isJsonObject()) {
                a.options.add(firstOf(op.getAsJsonObject(),
                        "id", "value", "label"));
            } else if (op.isJsonPrimitive()) {
                a.options.add(op.getAsString());
            }
        }
        a.verb = str(o, "verb");
        if (o.has("params") && o.get("params").isJsonObject()) {
            for (var e : o.getAsJsonObject("params").entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    a.params.put(e.getKey(),
                            e.getValue().getAsString());
                }
            }
        }
        return a;
    }

    private static String line(JsonObject o) {
        String what = firstOf(o, "what", "label", "title");
        String how = firstOf(o, "how", "fix", "command");
        return how.isBlank() ? what : what + "  →  " + how;
    }

    private static JsonArray arr(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray()
                ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive()
                ? o.get(key).getAsString() : "";
    }

    private static String firstOf(JsonObject o, String... keys) {
        for (String k : keys) {
            String v = str(o, k);
            if (!v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static int num(JsonObject o, String key) {
        try {
            return o.has(key) ? o.get(key).getAsInt() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
