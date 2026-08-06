package org.polari.shell.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.polari.shell.core.config.InstanceConfig;
import org.polari.shell.core.net.NetworkContext;
import org.polari.shell.core.net.ReachabilityProbe;

class ReachabilityTest {

    private InstanceConfig local(String... cidrs) {
        InstanceConfig i = new InstanceConfig();
        i.id = "prf-a";
        i.displayName = "Climate Lab";
        i.reachability.scope = "local";
        i.reachability.networkKind = "home";
        i.reachability.networkName = "Etts Home Network";
        i.reachability.hint.cidrs = List.of(cidrs);
        return i;
    }

    @Test
    void cidrMembership() {
        assertTrue(NetworkContext.inCidr("192.168.0.42",
                "192.168.0.0/24"));
        assertFalse(NetworkContext.inCidr("192.168.1.42",
                "192.168.0.0/24"));
        assertTrue(NetworkContext.inCidr("10.8.0.5", "10.0.0.0/8"));
        assertFalse(NetworkContext.inCidr("not-an-ip",
                "192.168.0.0/24"));
        assertFalse(NetworkContext.inCidr("192.168.0.1",
                "192.168.0.0/40"));
    }

    @Test
    void classificationTable() {
        InstanceConfig inst = local("192.168.0.0/24");
        // no interfaces up -> offline
        assertEquals(ReachabilityProbe.OFFLINE,
                ReachabilityProbe.classify(false, false,
                        "ConnectException", inst,
                        List.of()).state());
        // on a foreign subnet -> wrong-network
        var wrong = ReachabilityProbe.classify(false, false,
                "ConnectException", inst, List.of("10.0.0.7"));
        assertEquals(ReachabilityProbe.WRONG_NETWORK, wrong.state());
        assertTrue(wrong.evidence().contains("10.0.0.7"));
        assertTrue(wrong.evidence().contains("192.168.0.0/24"));
        // on the declared subnet but no answer -> instance-down
        assertEquals(ReachabilityProbe.INSTANCE_DOWN,
                ReachabilityProbe.classify(false, false,
                        "ConnectException", inst,
                        List.of("192.168.0.42")).state());
        // HTTP answered unhealthily -> instance-down regardless
        assertEquals(ReachabilityProbe.INSTANCE_DOWN,
                ReachabilityProbe.classify(true, false, "HTTP 503",
                        inst, List.of("10.0.0.7")).state());
        // web scope failure stays instance-down (never wrong-network)
        InstanceConfig web = new InstanceConfig();
        web.reachability.scope = "web";
        assertEquals(ReachabilityProbe.INSTANCE_DOWN,
                ReachabilityProbe.classify(false, false, "timeout",
                        web, List.of("10.0.0.7")).state());
        // success wins over everything
        assertEquals(ReachabilityProbe.REACHABLE,
                ReachabilityProbe.classify(true, true, "", inst,
                        List.of()).state());
    }

    @Test
    void advisoryNamesTheNetworkAndKind() {
        InstanceConfig inst = local("192.168.0.0/24");
        var wrong = ReachabilityProbe.classify(false, false, "x",
                inst, List.of("10.0.0.7"));
        String text = ReachabilityProbe.advisory(inst, wrong);
        assertTrue(text.contains("Climate Lab"));
        assertTrue(text.contains("'Etts Home Network' home network"));
        assertTrue(text.contains("Connect to that network"));
    }
}
