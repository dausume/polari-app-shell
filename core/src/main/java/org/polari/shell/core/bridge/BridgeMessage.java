package org.polari.shell.core.bridge;

import com.google.gson.JsonObject;

/** The S4 envelope. */
public class BridgeMessage {
    public int v = 1;
    public String id = "";
    public String type = "";
    public JsonObject payload = new JsonObject();

    public static JsonObject ok(String id, JsonObject payload) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("ok", true);
        o.add("payload", payload == null ? new JsonObject()
                : payload);
        return o;
    }

    public static JsonObject error(String id, String code,
                                   String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("ok", false);
        o.add("error", err);
        return o;
    }
}
