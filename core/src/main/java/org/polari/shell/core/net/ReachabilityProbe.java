package org.polari.shell.core.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.SSLContext;

import org.polari.shell.core.config.InstanceConfig;

/**
 * Probe first, infer second (plan B3). The ONLY authoritative
 * signal is the identity endpoint answering with the expected
 * instanceId; CIDR hints merely choose between wrong-network and
 * instance-down phrasings when the probe fails. Evidence strings
 * always name what was observed — the advisory advises, it NEVER
 * blocks (knobs-and-suggestions).
 */
public final class ReachabilityProbe {

    public static final String REACHABLE = "reachable";
    public static final String WRONG_NETWORK = "wrong-network";
    public static final String INSTANCE_DOWN = "instance-down";
    public static final String OFFLINE = "offline";
    public static final String WRONG_INSTANCE = "wrong-instance";
    public static final String UNKNOWN = "unknown";

    public record Result(String state, String evidence) {}

    private ReachabilityProbe() {}

    /** Live probe: GET identity over the instance's pinned trust. */
    public static Result probe(InstanceConfig inst,
                               SSLContext sslContext) {
        String url = !inst.reachability.hint.probeUrl.isBlank()
                ? inst.reachability.hint.probeUrl
                : inst.identityUrl;
        if (url == null || url.isBlank()) {
            return classify(false, false, "no probe URL configured",
                    inst, NetworkContext.localAddresses());
        }
        try {
            HttpClient.Builder b = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3));
            if (sslContext != null) {
                b.sslContext(sslContext);
            }
            HttpResponse<String> resp = b.build().send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(3))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 == 2) {
                String seenId = idFrom(resp.body());
                boolean match = inst.instanceId == null
                        || inst.instanceId.isBlank()
                        || inst.instanceId.equals(seenId);
                if (!match) {
                    return new Result(WRONG_INSTANCE,
                            "identity answered but instanceId is '"
                            + seenId + "', expected '"
                            + inst.instanceId
                            + "' — wrong URL or a different "
                            + "instance now lives there");
                }
                return new Result(REACHABLE,
                        "identity endpoint answered with the "
                        + "expected instance");
            }
            return classify(true, false,
                    "identity endpoint answered HTTP "
                    + resp.statusCode(), inst,
                    NetworkContext.localAddresses());
        } catch (Exception e) {
            return classify(false, false,
                    e.getClass().getSimpleName() + ": "
                    + e.getMessage(), inst,
                    NetworkContext.localAddresses());
        }
    }

    /**
     * Pure classification — unit-tested truth table. reachedHttp
     * means TCP+TLS worked but the answer was wrong (server up,
     * service unhealthy).
     */
    public static Result classify(boolean reachedHttp,
                                  boolean probeOk,
                                  String rawError,
                                  InstanceConfig inst,
                                  List<String> localAddrs) {
        if (probeOk) {
            return new Result(REACHABLE, "probe succeeded");
        }
        if (localAddrs.isEmpty()) {
            return new Result(OFFLINE,
                    "no network interface is up (" + rawError + ")");
        }
        String scope = inst.reachability.scope;
        List<String> cidrs = inst.reachability.hint.cidrs;
        String addrs = String.join(", ", localAddrs);
        if (reachedHttp) {
            return new Result(INSTANCE_DOWN,
                    "the server answered but not healthily: "
                    + rawError);
        }
        if ("local".equals(scope) && !cidrs.isEmpty()) {
            boolean onDeclared = localAddrs.stream()
                    .anyMatch(a -> NetworkContext.inAnyCidr(a, cidrs));
            if (!onDeclared) {
                return new Result(WRONG_NETWORK,
                        "your addresses: " + addrs + "; expected "
                        + String.join(", ", cidrs)
                        + " (heuristic — subnets can collide across "
                        + "unrelated networks)");
            }
            return new Result(INSTANCE_DOWN,
                    "you appear to be on the declared network ("
                    + addrs + ") but the instance is not "
                    + "answering: " + rawError);
        }
        if ("local".equals(scope)) {
            return new Result(WRONG_NETWORK,
                    "instance is declared local-only and did not "
                    + "answer (" + rawError + "); your addresses: "
                    + addrs + " — no subnet hint was declared, so "
                    + "this may also be the instance being down");
        }
        if ("web".equals(scope) || "mesh".equals(scope)) {
            return new Result(INSTANCE_DOWN,
                    "web-accessible instance did not answer ("
                    + rawError + ") — the instance may be down or "
                    + "this network may block it");
        }
        return new Result(UNKNOWN, rawError);
    }

    private static String idFrom(String body) {
        try {
            JsonObject o = new Gson().fromJson(body,
                    JsonObject.class);
            return o != null && o.has("instanceId")
                    ? o.get("instanceId").getAsString() : "";
        } catch (JsonSyntaxException e) {
            return "";
        }
    }

    /** The advisory sentence (plan B3 UX) — built here so every
     *  platform phrases it identically. */
    public static String advisory(InstanceConfig inst,
                                  Result result) {
        String kind = inst.reachability.networkKind.isBlank()
                ? "" : inst.reachability.networkKind + " ";
        String name = inst.reachability.networkName.isBlank()
                ? "its network"
                : "the '" + inst.reachability.networkName + "' "
                  + kind + "network";
        String title = inst.displayName.isBlank()
                ? inst.id : inst.displayName;
        return switch (result.state()) {
            case WRONG_NETWORK -> title + " lives on " + name
                    + " — you don't appear to be on it. Connect to "
                    + "that network to use this app. ("
                    + result.evidence() + ")";
            case INSTANCE_DOWN -> title
                    + " is not answering. " + result.evidence();
            case OFFLINE -> "No network connectivity. "
                    + result.evidence();
            case WRONG_INSTANCE -> "This address no longer answers "
                    + "as " + title + ". " + result.evidence();
            default -> title + ": " + result.evidence();
        };
    }
}
