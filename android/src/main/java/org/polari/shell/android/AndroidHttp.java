package org.polari.shell.android;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

import org.polari.shell.core.config.ConfigLoader;
import org.polari.shell.core.config.InstanceConfig;
import org.polari.shell.core.config.ShellConfig;
import org.polari.shell.core.net.NetworkContext;
import org.polari.shell.core.net.ReachabilityProbe;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * The two network calls :core does with java.net.http (absent on
 * Android), redone with HttpsURLConnection. Classification and
 * advisory phrasing stay in :core — one truth table, every
 * platform.
 */
final class AndroidHttp {

    private AndroidHttp() {}

    static ReachabilityProbe.Result probe(InstanceConfig inst,
                                          SSLContext trust) {
        String url = !inst.reachability.hint.probeUrl.isBlank()
                ? inst.reachability.hint.probeUrl
                : inst.identityUrl;
        if (url == null || url.isBlank()) {
            return ReachabilityProbe.classify(false, false,
                    "no probe URL configured", inst,
                    NetworkContext.localAddresses());
        }
        try {
            HttpURLConnection conn = open(url, trust);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            if (code / 100 == 2) {
                JsonObject o = new Gson().fromJson(
                        readAll(conn.getInputStream()),
                        JsonObject.class);
                String seen = o != null && o.has("instanceId")
                        ? o.get("instanceId").getAsString() : "";
                boolean match = inst.instanceId == null
                        || inst.instanceId.isBlank()
                        || inst.instanceId.equals(seen);
                return match
                        ? new ReachabilityProbe.Result(
                                ReachabilityProbe.REACHABLE,
                                "identity endpoint answered with "
                                + "the expected instance")
                        : new ReachabilityProbe.Result(
                                ReachabilityProbe.WRONG_INSTANCE,
                                "identity answered but instanceId "
                                + "is '" + seen + "', expected '"
                                + inst.instanceId + "'");
            }
            return ReachabilityProbe.classify(true, false,
                    "identity endpoint answered HTTP " + code,
                    inst, NetworkContext.localAddresses());
        } catch (Exception e) {
            return ReachabilityProbe.classify(false, false,
                    e.getClass().getSimpleName() + ": "
                    + e.getMessage(), inst,
                    NetworkContext.localAddresses());
        }
    }

    /** Redeem an enrollment token; the response IS a registration
     *  document. Server-side single-use semantics surface verbatim. */
    static ShellConfig redeem(String redeemUrl, String token,
                              String deviceLabel, SSLContext trust,
                              StringBuilder errorOut) {
        try {
            HttpURLConnection conn = open(redeemUrl, trust);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type",
                    "application/json");
            JsonObject body = new JsonObject();
            body.addProperty("token", token);
            body.addProperty("platform", "android");
            body.addProperty("deviceLabel", deviceLabel);
            conn.getOutputStream().write(
                    body.toString().getBytes(StandardCharsets.UTF_8));
            int code = conn.getResponseCode();
            String payload = readAll(code / 100 == 2
                    ? conn.getInputStream()
                    : conn.getErrorStream());
            if (code / 100 != 2) {
                errorOut.append("redeem refused (HTTP ")
                        .append(code).append("): ").append(payload);
                return null;
            }
            return ConfigLoader.parse(payload).orElseGet(() -> {
                errorOut.append("redeem answered but not with a "
                        + "registration document");
                return null;
            });
        } catch (Exception e) {
            errorOut.append(e.getClass().getSimpleName())
                    .append(": ").append(e.getMessage());
            return null;
        }
    }

    private static HttpURLConnection open(String url,
                                          SSLContext trust)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL(url).openConnection();
        if (trust != null && conn instanceof HttpsURLConnection https) {
            https.setSSLSocketFactory(trust.getSocketFactory());
        }
        return conn;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        // toString(Charset) is API 33; the byte[] route is 26-safe.
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static SSLContext trustFor(List<String> caPems) {
        try {
            return org.polari.shell.core.tls.InstanceTrust
                    .sslContext(caPems);
        } catch (Exception e) {
            return null;
        }
    }
}
