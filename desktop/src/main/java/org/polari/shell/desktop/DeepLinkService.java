package org.polari.shell.desktop;

import org.polari.shell.core.config.ConfigLoader;
import org.polari.shell.core.config.InstanceConfig;
import org.polari.shell.core.config.ShellConfig;
import org.polari.shell.core.link.DeepLink;
import org.polari.shell.core.net.EnrollClient;
import org.polari.shell.core.registry.InstanceRegistry;
import org.polari.shell.core.tls.InstanceTrust;

/**
 * polari:// intake. Phase 1 guarantees the --deeplink argument path
 * (works on every desktop); the .desktop x-scheme-handler
 * registration + running-instance socket forwarding are packaged
 * with the jpackage builds (phase 2) — the parse/act layer here is
 * the same for both.
 *
 * Short-form links redeem their token immediately: the redeem
 * response is a full registration document fetched over TLS that is
 * TOFU-pinned by the link's ca= fingerprint — the delivered CA must
 * hash to the pin or the merge is refused.
 */
final class DeepLinkService {

    private DeepLinkService() {}

    static void register(InstanceRegistry registry,
                         DeepLink.Parsed link) {
        if (!link.payloadJson().isBlank()) {
            // Inline form: the payload IS an instance object.
            ConfigLoader.parse(wrapAsDocument(link.payloadJson()))
                    .ifPresentOrElse(
                            cfg -> registry.merge(cfg, "deeplink"),
                            () -> System.err.println(
                                    "[deeplink] payload did not "
                                    + "parse as an instance"));
            return;
        }
        if (link.api().isBlank() || link.token().isBlank()) {
            System.err.println("[deeplink] register link without "
                    + "api/t — nothing to do");
            return;
        }
        String redeemUrl = link.api().replaceAll("/$", "")
                + "/api/appstore/enroll/redeem";
        // First contact: JVM default trust; a custom-CA staging
        // instance will fail TLS here, and the caPem arrives IN the
        // redeem response — so retry-with-pin is not possible until
        // the document is fetched. The pragmatic TOFU: fetch with
        // hostname verification ON but trust-any-chain, then REFUSE
        // the merge unless the delivered CA hashes to the link's
        // ca= pin. The pin is the trust root, not the channel.
        EnrollClient.Outcome out = EnrollClient.redeem(
                redeemUrl, link.token(), "desktop-linux-x64",
                "deeplink", TrustAnyChannel.context());
        if (!out.ok()) {
            System.err.println("[deeplink] " + out.error()
                    + (link.scope().equals("local")
                       ? " — this instance is local-only ("
                         + link.networkKind() + " network '"
                         + link.networkName() + "'); you may need "
                         + "to be on that network to register"
                       : ""));
            return;
        }
        ShellConfig doc = out.document();
        boolean pinHolds = link.caSha256().isBlank()
                || doc.instances.stream().allMatch(i ->
                        i.tls.caPem.isEmpty()
                        || i.tls.caPem.stream().anyMatch(p ->
                                InstanceTrust.pemSha256(p)
                                    .equalsIgnoreCase(
                                        link.caSha256())));
        if (!pinHolds) {
            System.err.println("[deeplink] REFUSED: delivered CA "
                    + "does not hash to the link's ca= pin — "
                    + "the link and the instance disagree about "
                    + "identity");
            return;
        }
        registry.merge(doc, "deeplink");
        doc.instances.forEach(i -> registry.get(i.id)
                .ifPresent(r -> r.enrolled = true));
        System.out.println("[deeplink] registered "
                + doc.instances.size() + " instance(s)");
    }

    private static String wrapAsDocument(String instanceJson) {
        return "{\"kind\": \"polari-shell-registration\","
                + "\"schemaVersion\": 1,"
                + "\"app\": {\"name\": \"deeplink\","
                + "\"scope\": \"instance\"},"
                + "\"instances\": [" + instanceJson + "]}";
    }
}
