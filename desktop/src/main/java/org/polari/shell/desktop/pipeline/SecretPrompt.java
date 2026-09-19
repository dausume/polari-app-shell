package org.polari.shell.desktop.pipeline;

/**
 * Asks the PERSON at the keyboard for a secret value (ci-11b).
 *
 * <p>The page never sees it and never supplies it: when a verb
 * declares {@code "stdin": "secret"} the shell itself opens a native
 * dialog, hands the char[] straight to the child's stdin and wipes
 * it. An interface so the runner is testable without a display.
 *
 * @return the value, or null when the person cancelled
 */
@FunctionalInterface
public interface SecretPrompt {
    char[] ask(String title, String explain);

    /** Headless/refusing default — a run that needs a secret fails
     *  honestly rather than silently sending nothing. */
    SecretPrompt REFUSE = (t, e) -> null;
}
