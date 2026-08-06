package org.polari.shell.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.polari.shell.core.link.DeepLink;

class DeepLinkTest {

    @Test
    void shortFormCarriesAllAdvisoryParams() {
        // The backend's deep_link builder emits exactly this shape
        // (selftest_appstore pins the other side).
        var p = DeepLink.parse(
                "polari://register?api=https%3A%2F%2Fapi.prf.test"
                + "&t=enr-abc123.s3cret&ca=deadbeef"
                + "&scope=local&nk=home&nn=Etts%20home%20network")
                .orElseThrow();
        assertEquals(DeepLink.Kind.REGISTER, p.kind());
        assertEquals("https://api.prf.test", p.api());
        assertEquals("enr-abc123.s3cret", p.token());
        assertEquals("deadbeef", p.caSha256());
        assertEquals("local", p.scope());
        assertEquals("home", p.networkKind());
        assertEquals("Etts home network", p.networkName());
    }

    @Test
    void inlinePayloadFormDecodes() {
        String json = "{\"id\": \"prf-b\"}";
        String b64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        var p = DeepLink.parse("polari://register?payload=" + b64)
                .orElseThrow();
        assertEquals(json, p.payloadJson());
    }

    @Test
    void oauthCallbackAndForeignSchemes() {
        assertEquals(DeepLink.Kind.OAUTH_CALLBACK,
                DeepLink.parse("polari://oauth/callback?code=x&state=y")
                        .orElseThrow().kind());
        assertTrue(DeepLink.parse("https://not-polari.example")
                .isEmpty());
        assertTrue(DeepLink.parse(null).isEmpty());
    }
}
