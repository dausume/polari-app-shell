package org.polari.shell.core.config;

import java.util.ArrayList;
import java.util.List;

/**
 * The registration document v1 (config/polari-shell.schema.json).
 * Gson-mapped POJO — field names ARE the wire names; changing one is
 * a schema change and needs the backend (appstore_payloads) moved in
 * lockstep.
 */
public class ShellConfig {
    public String kind = "";
    public int schemaVersion = 0;
    public App app = new App();
    public List<InstanceConfig> instances = new ArrayList<>();
    public Enrollment enrollment; // null = not pre-enrolled

    public static class App {
        public String name = "";
        public String title = "";
        public String scope = "instance";
        public String appName = "";
        public String startRoute = "";
        public String brandColor = "";
        public String icon = "";
    }

    public static class Enrollment {
        public String token = "";
        public String redeemUrl = "";
        public String expiresAt = "";
    }

    public boolean looksValid() {
        return "polari-shell-registration".equals(kind)
                && schemaVersion == 1 && instances != null
                && !instances.isEmpty();
    }
}
