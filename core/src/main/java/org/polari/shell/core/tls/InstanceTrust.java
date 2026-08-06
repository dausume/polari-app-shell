package org.polari.shell.core.tls;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.polari.shell.core.config.InstanceConfig;

/**
 * Per-instance trust — plan §B1's TLS story. The delivered caPem
 * builds an SSLContext scoped to THAT instance's HTTP calls (probe,
 * redeem, token endpoint); nothing global ever mutates, and there is
 * deliberately no trust-all fallback. The caSha256 fingerprint is
 * the deep-link TOFU pin and the CEF onCertificateError check.
 */
public final class InstanceTrust {

    private InstanceTrust() {}

    /** sha256 hex of the PEM BYTES (matches the backend's
     *  appstore_payloads.load_ca fingerprint). */
    public static String pemSha256(String pem) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] d = sha.digest(
                    pem.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean matchesPin(InstanceConfig.Tls tls) {
        if (tls == null || tls.caPem.isEmpty()
                || tls.caSha256 == null || tls.caSha256.isBlank()) {
            return false;
        }
        return tls.caPem.stream()
                .anyMatch(p -> pemSha256(p)
                        .equalsIgnoreCase(tls.caSha256));
    }

    /** SSLContext trusting exactly the delivered CAs; null when the
     *  instance ships none (public-CA deployment → JVM default). */
    public static SSLContext sslContext(List<String> caPems)
            throws Exception {
        if (caPems == null || caPems.isEmpty()) {
            return null;
        }
        CertificateFactory cf =
                CertificateFactory.getInstance("X.509");
        KeyStore ks = KeyStore.getInstance(
                KeyStore.getDefaultType());
        ks.load(null, null);
        int i = 0;
        for (String pem : caPems) {
            for (Certificate cert : cf.generateCertificates(
                    new ByteArrayInputStream(
                            pem.getBytes(StandardCharsets.UTF_8)))) {
                ks.setCertificateEntry("polari-ca-" + (i++),
                        (X509Certificate) cert);
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }
}
