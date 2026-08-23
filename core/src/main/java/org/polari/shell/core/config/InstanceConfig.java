package org.polari.shell.core.config;

import java.util.ArrayList;
import java.util.List;

/** One registered Polari instance as delivered in the registration
 *  document (S1). */
public class InstanceConfig {
    public String id = "";
    public String displayName = "";
    public String webUrl = "";
    public String apiUrl = "";
    public String identityUrl = "";
    public String instanceId = "";
    public Auth auth = new Auth();
    public Tls tls = new Tls();
    public Reachability reachability = new Reachability();

    public static class Auth {
        public String authority = "";
        public String realm = "";
        public String clientId = "";
        public String pkce = "S256";
        public String scope = "";
        public String shellRedirectUri = "";
        public String loginHint = "";
    }

    public static class Tls {
        public List<String> caPem = new ArrayList<>();
        /**
         * CA FILE references, resolved at trust-build time on THIS
         * device. Exists because a config can be baked before its
         * CA is born: the store deb is built pre-install, the isle
         * CA is minted BY core-install — found live 2026-08-21 when
         * the first store open after a flawless install failed PKIX.
         * Absent/unreadable paths are skipped (the CA simply is not
         * minted yet); present files join caPem in the pinned trust.
         */
        public List<String> caFile = new ArrayList<>();
        public String caSha256 = "";
    }

    /**
     * The instance's declared reachability. scope=local is the case
     * the advisory exists for; "mesh" is a named placeholder the
     * shell treats as web (no mesh behavior exists yet).
     */
    public static class Reachability {
        public String scope = "web";
        public String networkKind = "";
        public String networkName = "";
        public Hint hint = new Hint();

        public static class Hint {
            /** ADVISORY only — RFC1918 subnets collide across
             *  unrelated NATs; the probeUrl is authoritative. */
            public List<String> cidrs = new ArrayList<>();
            public String probeUrl = "";
        }
    }
}
