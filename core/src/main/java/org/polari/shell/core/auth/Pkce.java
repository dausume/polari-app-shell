package org.polari.shell.core.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * RFC 7636 PKCE, S256 only — "using sha" in the original ask IS the
 * S256 code-challenge method, the same one the Angular SPA's
 * oidc-client-ts uses. Phase 1 desktop lets the SPA run its own
 * flow; this is the shared plumbing phase-2 native auth and mobile
 * (Custom Tabs / ASWebAuthenticationSession) build on.
 */
public final class Pkce {

    private Pkce() {}

    /** 43..128 chars of [A-Za-z0-9-._~] per RFC 7636 §4.1. */
    public static String newVerifier(SecureRandom random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(bytes);
    }

    /** code_challenge = BASE64URL(SHA256(ASCII(verifier))), no
     *  padding — RFC 7636 §4.2. */
    public static String challengeS256(String verifier) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(
                    verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "JVM without SHA-256", e);
        }
    }
}
