package org.polari.shell.desktop.hardware;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;

/**
 * Step 2, the gate: the sha256 of firmware.hex against the one
 * polari-install.json declares (`hex_sha256`). Only a MISMATCH refuses,
 * and the refusal names both digests. Anything else (no manifest, no
 * digest field, no README) is ADVICE — a sentence, never a block.
 */
public final class BundleGate {

    /** passed=false means REFUSE; advice is shown either way. */
    public record Verdict(boolean passed, String sentence,
                          List<String> advice) {}

    private BundleGate() {}

    public static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static Verdict check(Path hex, JsonObject install)
            throws Exception {
        List<String> advice = new ArrayList<>();
        if (hex == null || !Files.isRegularFile(hex)) {
            return new Verdict(false, "This bundle has no firmware.hex, "
                    + "so there is nothing to flash.", advice);
        }
        String actual = sha256(hex);
        String declared = install == null || !install.has("hex_sha256")
                || install.get("hex_sha256").isJsonNull() ? ""
                : install.get("hex_sha256").getAsString()
                        .trim().toLowerCase();
        if (declared.isEmpty()) {
            advice.add("polari-install.json does not declare a "
                    + "hex_sha256, so this firmware could not be "
                    + "checked against its manifest. The file's own "
                    + "sha256 is " + actual + ".");
            return new Verdict(true, "Not checked against a manifest "
                    + "(advice only).", advice);
        }
        if (!declared.equals(actual)) {
            return new Verdict(false, "Refused: firmware.hex is "
                    + actual + " but polari-install.json says "
                    + declared + ". The file was changed after it "
                    + "was built.", advice);
        }
        return new Verdict(true, "firmware.hex matches its manifest ("
                + actual.substring(0, 12) + "...).", advice);
    }
}
