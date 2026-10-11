package org.polari.shell.desktop.hardware;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes bridge.properties: keeps the bundle's own lines (comments,
 * order, other keys) and sets serial.device / grpc.enabled /
 * grpc.target. The serial device lives only in this local file; it is
 * never sent to Polari.
 */
public final class BridgeProperties {

    private BridgeProperties() {}

    public static String grpcTarget(String text) {
        for (String l : text.split("\n")) {
            String t = l.trim();
            if (t.startsWith("grpc.target=")) {
                return t.substring("grpc.target=".length()).trim();
            }
        }
        return "";
    }

    public static String render(String existing, String port,
                                String grpcTarget) {
        Map<String, String> set = new LinkedHashMap<>();
        set.put("source", "serial");
        set.put("serial.device", port);
        set.put("grpc.enabled", "true");
        set.put("grpc.target", grpcTarget);
        List<String> out = new ArrayList<>();
        java.util.Set<String> done = new java.util.HashSet<>();
        for (String line : existing.split("\n", -1)) {
            String t = line.trim();
            int eq = t.indexOf('=');
            String key = !t.startsWith("#") && eq > 0
                    ? t.substring(0, eq).trim() : null;
            if (key != null && set.containsKey(key)) {
                if (done.add(key)) {
                    out.add(key + "=" + set.get(key));
                }
            } else {
                out.add(line);
            }
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) {
            out.remove(out.size() - 1);
        }
        for (Map.Entry<String, String> e : set.entrySet()) {
            if (!done.contains(e.getKey())) {
                out.add(e.getKey() + "=" + e.getValue());
            }
        }
        return String.join("\n", out) + "\n";
    }

    public static void write(Path file, String port,
                             String grpcTarget) throws Exception {
        String existing = Files.exists(file)
                ? Files.readString(file, StandardCharsets.UTF_8) : "";
        Files.writeString(file, render(existing, port, grpcTarget),
                StandardCharsets.UTF_8);
    }
}
