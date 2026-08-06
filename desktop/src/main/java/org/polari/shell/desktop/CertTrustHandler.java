package org.polari.shell.desktop;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.cef.browser.CefBrowser;
import org.cef.callback.CefCallback;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefRequestHandlerAdapter;

import org.polari.shell.core.config.InstanceConfig;
import org.polari.shell.core.tls.InstanceTrust;

/**
 * CA pinning for the embedded browser. This JCEF's
 * onCertificateError callback does NOT expose the presented chain
 * (no CefSSLInfo in the 135.x Java API), so chain-hash pinning is
 * impossible here. The honest substitute, layered:
 *
 *   1. the erroring URL's host must be one the instance DECLARED
 *      (webUrl / apiUrl / authority / probeUrl) — anything else
 *      refuses outright;
 *   2. a JVM-side TLS handshake to that host:port, validated
 *      against the instance's pinned CA (real X.509 validation),
 *      must succeed — the JVM sees the actually-served chain even
 *      though CEF won't show it to us.
 *
 * A same-network TOCTOU window between the two handshakes remains —
 * documented, not hidden. Verdicts are cached per host:port.
 */
final class CertTrustHandler extends CefRequestHandlerAdapter {

    private final InstanceConfig inst;
    private final Set<String> declaredHosts;
    private final Map<String, Boolean> verdicts =
            new ConcurrentHashMap<>();

    CertTrustHandler(InstanceConfig inst) {
        this.inst = inst;
        this.declaredHosts = Stream.of(
                        inst.webUrl, inst.apiUrl, inst.identityUrl,
                        inst.auth.authority,
                        inst.reachability.hint.probeUrl)
                .filter(u -> u != null && !u.isBlank())
                .map(CertTrustHandler::hostOf)
                .filter(h -> !h.isBlank())
                .collect(Collectors.toSet());
    }

    @Override
    public boolean onCertificateError(CefBrowser browser,
            CefLoadHandler.ErrorCode certError, String requestUrl,
            CefCallback callback) {
        String host = hostOf(requestUrl);
        int port = portOf(requestUrl);
        if (inst.tls.caPem.isEmpty()) {
            System.err.println("[tls] REFUSED " + requestUrl
                    + " (" + certError + "): no CA pinned for "
                    + "instance '" + inst.id + "' — import the CA "
                    + "into the OS store or re-register with a "
                    + "config that carries tls.caPem");
            callback.cancel();
            return true;
        }
        if (!declaredHosts.contains(host)) {
            System.err.println("[tls] REFUSED " + requestUrl
                    + ": host is not one this instance declared ("
                    + String.join(", ", declaredHosts) + ")");
            callback.cancel();
            return true;
        }
        boolean ok = verdicts.computeIfAbsent(host + ":" + port,
                k -> pinnedHandshake(host, port));
        if (ok) {
            callback.Continue();
        } else {
            System.err.println("[tls] REFUSED " + requestUrl
                    + ": served certificate does not validate "
                    + "against the pinned CA (pin "
                    + shortPin() + ")");
            callback.cancel();
        }
        return true;
    }

    /** Real X.509 validation of the served chain against exactly the
     *  delivered CA — via a throwaway JVM handshake. */
    private boolean pinnedHandshake(String host, int port) {
        try {
            SSLContext ctx = InstanceTrust.sslContext(
                    inst.tls.caPem);
            if (ctx == null) {
                return false;
            }
            try (SSLSocket socket = (SSLSocket) ctx
                    .getSocketFactory().createSocket(host, port)) {
                socket.setSoTimeout(4000);
                // Hostname verification via the HTTPS endpoint rules.
                var params = socket.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(params);
                socket.startHandshake();
                return true;
            }
        } catch (Exception e) {
            System.err.println("[tls] pinned handshake with " + host
                    + ":" + port + " failed: " + e.getMessage());
            return false;
        }
    }

    private String shortPin() {
        String pin = inst.tls.caSha256;
        return pin.length() > 12 ? pin.substring(0, 12) + "…" : pin;
    }

    private static String hostOf(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? "" : h;
        } catch (Exception e) {
            return "";
        }
    }

    private static int portOf(String url) {
        try {
            int p = URI.create(url).getPort();
            return p > 0 ? p : 443;
        } catch (Exception e) {
            return 443;
        }
    }
}
