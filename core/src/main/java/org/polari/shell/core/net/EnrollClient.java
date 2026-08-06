package org.polari.shell.core.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.net.ssl.SSLContext;

import org.polari.shell.core.config.ConfigLoader;
import org.polari.shell.core.config.ShellConfig;

/**
 * First-launch enrollment redemption: POST the one-time token to
 * /api/appstore/enroll/redeem over the instance's pinned trust; the
 * response IS a full registration document (S1) which the registry
 * merges. Single-use semantics are the server's — a replayed token
 * gets that server's named refusal, surfaced verbatim.
 */
public final class EnrollClient {

    public record Outcome(boolean ok, ShellConfig document,
                          String error) {}

    private EnrollClient() {}

    public static Outcome redeem(String redeemUrl, String token,
                                 String platform,
                                 String deviceLabel,
                                 SSLContext sslContext) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("token", token);
            body.addProperty("platform", platform);
            body.addProperty("deviceLabel", deviceLabel);
            HttpClient.Builder b = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5));
            if (sslContext != null) {
                b.sslContext(sslContext);
            }
            HttpResponse<String> resp = b.build().send(
                    HttpRequest.newBuilder(URI.create(redeemUrl))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type",
                                    "application/json")
                            .POST(HttpRequest.BodyPublishers
                                    .ofString(body.toString()))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                String detail = errorOf(resp.body());
                return new Outcome(false, null,
                        "redeem refused (HTTP " + resp.statusCode()
                        + "): " + detail);
            }
            return ConfigLoader.parse(resp.body())
                    .map(doc -> new Outcome(true, doc, ""))
                    .orElseGet(() -> new Outcome(false, null,
                            "redeem answered but not with a "
                            + "registration document"));
        } catch (Exception e) {
            return new Outcome(false, null,
                    e.getClass().getSimpleName() + ": "
                    + e.getMessage());
        }
    }

    private static String errorOf(String body) {
        try {
            JsonObject o = new Gson().fromJson(body,
                    JsonObject.class);
            return o != null && o.has("error")
                    ? o.get("error").getAsString() : body;
        } catch (Exception e) {
            return body;
        }
    }
}
