package org.polari.shell.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;
import org.polari.shell.core.auth.Pkce;

class PkceTest {

    @Test
    void rfc7636AppendixBVector() {
        // The spec's own worked example — if this fails, Keycloak
        // will refuse every code exchange.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                Pkce.challengeS256(
                        "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    void verifierShapeIsLegal() {
        String v = Pkce.newVerifier(new SecureRandom());
        assertTrue(v.length() >= 43 && v.length() <= 128);
        assertTrue(v.matches("[A-Za-z0-9\\-._~]+"));
    }
}
