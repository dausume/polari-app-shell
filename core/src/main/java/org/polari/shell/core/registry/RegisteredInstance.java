package org.polari.shell.core.registry;

import org.polari.shell.core.config.InstanceConfig;

/** One instance in the durable registry: the delivered config plus
 *  shell-maintained state. */
public class RegisteredInstance {
    public InstanceConfig config = new InstanceConfig();
    /** Where this registration came from: baked | sidecar | arg |
     *  deeplink | manual. */
    public String source = "";
    /** True once the enrollment token was redeemed. */
    public boolean enrolled = false;
    /** The user classified the network themselves (wizard). */
    public boolean userClassified = false;
    public LastProbe lastProbe = new LastProbe();
    /** A user "trust once/always" decision on an unpinned cert —
     *  recorded with evidence, never silent. */
    public String trustDecision = "";

    public static class LastProbe {
        public String at = "";
        /** reachable | wrong-network | instance-down | offline |
         *  unknown */
        public String state = "";
        public String evidence = "";
    }
}
