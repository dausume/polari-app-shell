package org.polari.shell.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.polari.shell.core.auth.OidcClient;
import org.polari.shell.core.bridge.BridgeRouter;
import org.polari.shell.core.config.InstanceConfig;
import org.polari.shell.core.tls.InstanceTrust;

class BridgeAndTrustTest {

    @Test
    void bridgeDispatchesAndRefusesHonestly() {
        BridgeRouter router = new BridgeRouter();
        router.on("shell.info", payload -> {
            JsonObject o = new JsonObject();
            o.addProperty("platform", "test");
            return o;
        });
        JsonObject resp = new Gson().fromJson(router.dispatch(
                "{\"v\":1,\"id\":\"42\",\"type\":\"shell.info\","
                + "\"payload\":{}}"), JsonObject.class);
        assertTrue(resp.get("ok").getAsBoolean());
        assertEquals("42", resp.get("id").getAsString());
        assertEquals("test", resp.getAsJsonObject("payload")
                .get("platform").getAsString());
        JsonObject unknown = new Gson().fromJson(router.dispatch(
                "{\"v\":1,\"id\":\"43\",\"type\":\"nope\"}"),
                JsonObject.class);
        assertFalse(unknown.get("ok").getAsBoolean());
        assertTrue(unknown.getAsJsonObject("error")
                .get("message").getAsString().contains("shell.info"));
    }

    @Test
    void pemFingerprintMatchesBackendConvention() {
        // Backend: hashlib.sha256(pem_bytes).hexdigest() — the two
        // sides must agree byte-for-byte for the TOFU pin to hold.
        String pem = "-----BEGIN CERTIFICATE-----\nabc\n"
                + "-----END CERTIFICATE-----\n";
        assertEquals(
                "be8429aadfcd8ca89dc64a3b4eaee78b346824435"
                + "bf4a1b2cef691dec28ba7ed",
                InstanceTrust.pemSha256(pem));
        InstanceConfig.Tls tls = new InstanceConfig.Tls();
        tls.caPem = List.of(pem);
        tls.caSha256 = InstanceTrust.pemSha256(pem);
        assertTrue(InstanceTrust.matchesPin(tls));
        tls.caSha256 = "0000";
        assertFalse(InstanceTrust.matchesPin(tls));
    }

    @Test
    void authorizeUrlCarriesPkceAndHint() {
        InstanceConfig.Auth auth = new InstanceConfig.Auth();
        auth.authority = "https://auth.test/realms/Polari/";
        auth.clientId = "polari-shell";
        auth.shellRedirectUri = "polari://oauth/callback";
        auth.scope = "openid profile";
        auth.loginHint = "dustin";
        String url = OidcClient.authorizeUrl(auth, "st",
                "challenge123");
        assertTrue(url.startsWith("https://auth.test/realms/Polari"
                + "/protocol/openid-connect/auth?"));
        assertTrue(url.contains("code_challenge_method=S256"));
        assertTrue(url.contains("code_challenge=challenge123"));
        assertTrue(url.contains(
                "redirect_uri=polari%3A%2F%2Foauth%2Fcallback"));
        assertTrue(url.contains("login_hint=dustin"));
    }
}
