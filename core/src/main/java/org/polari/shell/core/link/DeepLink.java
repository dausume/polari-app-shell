package org.polari.shell.core.link;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * polari:// deep links (plan S2). Two register forms:
 *
 *   polari://register?api=<apiBase>&t=<token>&ca=<caSha256>
 *                    &scope=local&nk=home&nn=Etts%20home%20network
 *   polari://register?payload=<base64url(S1 instance object)>
 *
 * plus the phase-2 OAuth callback polari://oauth/callback?... .
 * The short form's ca is the TOFU pin for the first trusted fetch;
 * scope/nk/nn let the shell phrase the network advisory even when
 * the redeem itself is unreachable — the new-requirement case.
 */
public final class DeepLink {

    public enum Kind { REGISTER, OAUTH_CALLBACK, UNKNOWN }

    public record Parsed(Kind kind, String api, String token,
                         String caSha256, String scope,
                         String networkKind, String networkName,
                         String payloadJson,
                         Map<String, String> raw) {}

    private DeepLink() {}

    public static Optional<Parsed> parse(String uri) {
        if (uri == null || !uri.startsWith("polari://")) {
            return Optional.empty();
        }
        String rest = uri.substring("polari://".length());
        String path = rest.contains("?")
                ? rest.substring(0, rest.indexOf('?')) : rest;
        Map<String, String> q = query(rest);
        if (path.equals("register")) {
            String payloadJson = "";
            if (q.containsKey("payload")) {
                try {
                    payloadJson = new String(
                            Base64.getUrlDecoder()
                                    .decode(q.get("payload")),
                            StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    return Optional.empty();
                }
            }
            return Optional.of(new Parsed(Kind.REGISTER,
                    q.getOrDefault("api", ""),
                    q.getOrDefault("t", ""),
                    q.getOrDefault("ca", ""),
                    q.getOrDefault("scope", ""),
                    q.getOrDefault("nk", ""),
                    q.getOrDefault("nn", ""),
                    payloadJson, q));
        }
        if (path.equals("oauth/callback")) {
            return Optional.of(new Parsed(Kind.OAUTH_CALLBACK,
                    "", "", "", "", "", "", "", q));
        }
        return Optional.of(new Parsed(Kind.UNKNOWN,
                "", "", "", "", "", "", "", q));
    }

    private static Map<String, String> query(String rest) {
        Map<String, String> out = new LinkedHashMap<>();
        int idx = rest.indexOf('?');
        if (idx < 0 || idx == rest.length() - 1) {
            return out;
        }
        for (String pair : rest.substring(idx + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(
                URLDecoder.decode(pair.substring(0, eq),
                        StandardCharsets.UTF_8),
                URLDecoder.decode(pair.substring(eq + 1),
                        StandardCharsets.UTF_8));
        }
        return out;
    }
}
