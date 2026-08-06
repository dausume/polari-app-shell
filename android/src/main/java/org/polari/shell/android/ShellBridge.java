package org.polari.shell.android;

import android.app.Activity;
import android.webkit.JavascriptInterface;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.polari.shell.core.bridge.BridgeRouter;
import org.polari.shell.core.registry.InstanceRegistry;
import org.polari.shell.core.registry.RegisteredInstance;

/**
 * The S4 bridge over @JavascriptInterface. The SPA's injected
 * polyfill calls PolariShellNative.request(json) synchronously;
 * the envelope + handlers are :core's BridgeRouter — identical
 * surface to desktop's CefMessageRouter.
 */
final class ShellBridge {

    private final BridgeRouter router = new BridgeRouter();

    ShellBridge(Activity activity, InstanceRegistry registry,
                RegisteredInstance current) {
        router.on("shell.info", p -> {
            JsonObject o = new JsonObject();
            o.addProperty("shellVersion", "0.1.0");
            o.addProperty("platform", "vr".equals(BuildConfig.FLAVOR)
                    ? "android-vr" : "android");
            o.addProperty("instanceId", current.config.id);
            return o;
        });
        router.on("shell.instance.list", p -> {
            JsonArray arr = new JsonArray();
            registry.all().forEach(r -> {
                JsonObject o = new JsonObject();
                o.addProperty("id", r.config.id);
                o.addProperty("displayName", r.config.displayName);
                JsonObject reach = new JsonObject();
                reach.addProperty("state", r.lastProbe.state);
                reach.addProperty("evidence", r.lastProbe.evidence);
                o.add("reachability", reach);
                arr.add(o);
            });
            JsonObject out = new JsonObject();
            out.add("instances", arr);
            return out;
        });
        router.on("shell.reachability.get", p -> {
            JsonObject o = new JsonObject();
            o.addProperty("state", current.lastProbe.state);
            o.addProperty("evidence", current.lastProbe.evidence);
            return o;
        });
    }

    @JavascriptInterface
    public String request(String json) {
        return router.dispatch(json);
    }
}
