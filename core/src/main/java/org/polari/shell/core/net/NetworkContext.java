package org.polari.shell.core.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What network am I on — desktop version (java.net.NetworkInterface
 * is reliable here; Android/iOS get platform implementations of the
 * same questions with honestly less precision).
 */
public final class NetworkContext {

    private NetworkContext() {}

    /** Non-loopback IPv4 addresses of interfaces that are up. */
    public static List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(
                    NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(
                        nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address
                            && !addr.isLoopbackAddress()) {
                        out.add(addr.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) {
            // no interfaces enumerable -> empty = "offline" signal
        }
        return out;
    }

    /** Pure IPv4 CIDR membership — testable without a network. */
    public static boolean inCidr(String address, String cidr) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) {
                return false;
            }
            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > 32) {
                return false;
            }
            long net = toLong(parts[0]);
            long addr = toLong(address);
            if (net < 0 || addr < 0) {
                return false;
            }
            long mask = prefix == 0 ? 0
                    : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            return (net & mask) == (addr & mask);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static boolean inAnyCidr(String address,
                                    List<String> cidrs) {
        return cidrs != null && cidrs.stream()
                .anyMatch(c -> inCidr(address, c));
    }

    private static long toLong(String dotted) {
        String[] o = dotted.trim().split("\\.");
        if (o.length != 4) {
            return -1;
        }
        long v = 0;
        for (String part : o) {
            int b = Integer.parseInt(part);
            if (b < 0 || b > 255) {
                return -1;
            }
            v = (v << 8) | b;
        }
        return v;
    }
}
