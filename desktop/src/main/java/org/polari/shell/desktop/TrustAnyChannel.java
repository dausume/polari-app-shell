package org.polari.shell.desktop;

import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The ONE deliberately-scoped exception to "no trust-all ever":
 * the deep-link TOFU fetch. A polari://register link carries the CA
 * FINGERPRINT but the CA itself only arrives inside the redeem
 * response — a strict channel could never bootstrap against a
 * self-signed staging instance. So this context is used for exactly
 * one request, and DeepLinkService REFUSES the result unless the
 * delivered CA hashes to the link's pin: the pin is the trust root,
 * the channel is just transport. Never use this anywhere else.
 */
final class TrustAnyChannel {

    private TrustAnyChannel() {}

    static SSLContext context() {
        try {
            TrustManager tm = new X509TrustManager() {
                @Override
                public void checkClientTrusted(
                        X509Certificate[] chain, String authType) {}

                @Override
                public void checkServerTrusted(
                        X509Certificate[] chain, String authType) {}

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {tm}, null);
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
