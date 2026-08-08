package org.polari.shell.desktop;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Runs a fixed argv on the host and captures its result (handoff
 * §31). The privileged install path is `pkexec ...` — pkexec pops
 * the polkit password dialog itself, so the shell never handles the
 * password. The command ALWAYS comes from HostInstall (validated
 * argv), never a page-supplied string.
 */
final class HostProcess {

    static final class Result {
        final int exitCode;
        final String output;
        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private HostProcess() {}

    static Result run(List<String> argv, long timeoutSeconds)
            throws Exception {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(),
                        StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
                if (out.length() > 200_000) {
                    out.append("… (truncated)\n");
                    break;
                }
            }
        }
        boolean done = p.waitFor(timeoutSeconds,
                java.util.concurrent.TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            return new Result(-1, out
                    + "\n[timed out after " + timeoutSeconds + "s]");
        }
        return new Result(p.exitValue(), out.toString());
    }
}
