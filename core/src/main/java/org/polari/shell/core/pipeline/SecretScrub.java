package org.polari.shell.core.pipeline;

/**
 * Keeps a typed secret out of everything a verb hands back (ci-11b).
 *
 * <p>The secret is prompted natively, written to the child's stdin
 * and then zeroed. It never reaches a page, a log or an argv — but a
 * CLI that echoes what it received would leak it through the run's
 * OUTPUT, so the output is scrubbed before the bridge reply is
 * built.
 *
 * <p>The search runs against the char[] directly: turning the secret
 * into a String to call {@code replace} would leave an immutable
 * copy on the heap that cannot be cleared.
 */
public final class SecretScrub {

    public static final String MASK = "********";

    /** Short values are not scrubbed: masking every "a" in the
     *  output would destroy it without protecting anything, and a
     *  secret this short is not a secret. */
    static final int MIN_LENGTH = 4;

    private SecretScrub() {}

    public static String scrub(String output, char[] secret) {
        if (output == null || output.isEmpty()
                || secret == null || secret.length < MIN_LENGTH) {
            return output;
        }
        StringBuilder sb = new StringBuilder(output.length());
        int i = 0;
        while (i < output.length()) {
            if (matchesAt(output, i, secret)) {
                sb.append(MASK);
                i += secret.length;
            } else {
                sb.append(output.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    private static boolean matchesAt(String s, int at,
                                     char[] secret) {
        if (at + secret.length > s.length()) {
            return false;
        }
        for (int k = 0; k < secret.length; k++) {
            if (s.charAt(at + k) != secret[k]) {
                return false;
            }
        }
        return true;
    }

    /** Overwrite a secret buffer in place. Always in a finally. */
    public static void wipe(char[] secret) {
        if (secret != null) {
            java.util.Arrays.fill(secret, '\0');
        }
    }
}
