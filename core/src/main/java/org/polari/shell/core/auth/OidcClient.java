package org.polari.shell.core.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.polari.shell.core.config.InstanceConfig;

/**
 * Authorization-code + PKCE S256 request plumbing (phase-2 wiring;
 * built + unit-tested now so mobile and Strategy-B desktop have a
 * tested seam). URL building is pure; the token calls ride
 * java.net.http with the instance's pinned SSLContext
 * (InstanceTrust) — never global trust.
 */
public final class OidcClient {

    private OidcClient() {}

    public static String authorizeUrl(InstanceConfig.Auth auth,
                                      String state,
                                      String challenge) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("client_id", auth.clientId);
        q.put("redirect_uri", auth.shellRedirectUri);
        q.put("response_type", "code");
        q.put("scope", auth.scope);
        q.put("state", state);
        q.put("code_challenge", challenge);
        q.put("code_challenge_method", "S256");
        if (auth.loginHint != null && !auth.loginHint.isBlank()) {
            q.put("login_hint", auth.loginHint);
        }
        StringBuilder sb = new StringBuilder(
                auth.authority.replaceAll("/$", ""))
                .append("/protocol/openid-connect/auth?");
        q.forEach((k, v) -> sb.append(k).append('=')
                .append(URLEncoder.encode(v, StandardCharsets.UTF_8))
                .append('&'));
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    public static String tokenEndpoint(InstanceConfig.Auth auth) {
        return auth.authority.replaceAll("/$", "")
                + "/protocol/openid-connect/token";
    }

    /** application/x-www-form-urlencoded body for the code
     *  exchange. */
    public static String codeExchangeBody(InstanceConfig.Auth auth,
                                          String code,
                                          String verifier) {
        return form(Map.of(
                "grant_type", "authorization_code",
                "client_id", auth.clientId,
                "redirect_uri", auth.shellRedirectUri,
                "code", code,
                "code_verifier", verifier));
    }

    public static String refreshBody(InstanceConfig.Auth auth,
                                     String refreshToken) {
        return form(Map.of(
                "grant_type", "refresh_token",
                "client_id", auth.clientId,
                "refresh_token", refreshToken));
    }

    private static String form(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        fields.forEach((k, v) -> sb.append(k).append('=')
                .append(URLEncoder.encode(v, StandardCharsets.UTF_8))
                .append('&'));
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }
}
