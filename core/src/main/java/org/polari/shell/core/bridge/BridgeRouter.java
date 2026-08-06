package org.polari.shell.core.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Platform-neutral dispatch for the S4 bridge: desktop's
 * CefMessageRouter, Android's @JavascriptInterface and iOS's
 * WKScriptMessageHandler all feed raw request JSON here and post the
 * returned JSON back to the page. Handlers are pure
 * JsonObject -> JsonObject.
 */
public class BridgeRouter {

    private static final Gson GSON = new Gson();
    private final Map<String, Function<JsonObject, JsonObject>>
            handlers = new LinkedHashMap<>();

    public void on(String type,
                   Function<JsonObject, JsonObject> handler) {
        handlers.put(type, handler);
    }

    /** Raw request json in, raw response json out — never throws. */
    public String dispatch(String requestJson) {
        BridgeMessage msg;
        try {
            msg = GSON.fromJson(requestJson, BridgeMessage.class);
        } catch (JsonSyntaxException e) {
            return BridgeMessage.error("", "bad-envelope",
                    e.getMessage()).toString();
        }
        if (msg == null || msg.type == null || msg.type.isBlank()) {
            return BridgeMessage.error(
                    msg == null ? "" : msg.id,
                    "bad-envelope", "missing type").toString();
        }
        Function<JsonObject, JsonObject> handler =
                handlers.get(msg.type);
        if (handler == null) {
            return BridgeMessage.error(msg.id, "unknown-type",
                    msg.type + " (known: "
                    + String.join(", ", handlers.keySet()) + ")")
                    .toString();
        }
        try {
            return BridgeMessage.ok(msg.id,
                    handler.apply(msg.payload)).toString();
        } catch (Exception e) {
            return BridgeMessage.error(msg.id, "handler-failed",
                    e.toString()).toString();
        }
    }
}
