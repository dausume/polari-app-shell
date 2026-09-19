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
public final class HostProcess {

    public static final class Result {
        public final int exitCode;
        public final String output;
        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private HostProcess() {}

    public static Result run(List<String> argv, long timeoutSeconds)
            throws Exception {
        return run(argv, timeoutSeconds, null);
    }

    /**
     * ci-11b: the same fixed-argv runner, with an optional SECRET on
     * the child's stdin. The value is written as bytes and the
     * buffer zeroed immediately; it is never an argv element, never
     * an environment variable, and never logged. `stdin` is null for
     * every other caller, so the store path is unchanged.
     */
    public static Result run(List<String> argv, long timeoutSeconds,
                             char[] stdin) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        writeStdin(p, stdin);
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

    /** Write the secret (if any) and close the pipe, so a CLI that
     *  reads a value from stdin sees EOF and does not hang. When
     *  there is no secret the pipe is still closed — a child that
     *  waits on stdin must not block the shell. */
    private static void writeStdin(Process p, char[] stdin)
            throws Exception {
        try (var os = p.getOutputStream()) {
            if (stdin == null || stdin.length == 0) {
                return;
            }
            java.nio.ByteBuffer bb = StandardCharsets.UTF_8.encode(
                    java.nio.CharBuffer.wrap(stdin));
            byte[] bytes = new byte[bb.remaining()];
            bb.get(bytes);
            try {
                os.write(bytes);
                os.write('\n');
                os.flush();
            } finally {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
        }
    }
}
