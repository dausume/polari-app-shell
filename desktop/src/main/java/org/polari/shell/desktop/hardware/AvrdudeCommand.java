package org.polari.shell.desktop.hardware;

import java.util.List;

/**
 * The one flash command, as a fixed argv list (never a shell string).
 * No -V: avrdude's default read-back verify stays on.
 */
public final class AvrdudeCommand {

    private AvrdudeCommand() {}

    /** Throws when the port or hex path would let a value act as an
     *  option or break the -U spec. */
    public static List<String> build(String port, String hexPath) {
        if (port == null || !port.startsWith("/dev/")
                || port.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    "not a serial device path: " + port);
        }
        if (hexPath == null || hexPath.isBlank()
                || hexPath.contains(":") || hexPath.startsWith("-")) {
            throw new IllegalArgumentException(
                    "firmware path cannot be used in an avrdude -U "
                    + "spec: " + hexPath);
        }
        return List.of("avrdude", "-c", "arduino", "-p", "atmega328p",
                "-P", port, "-b", "115200", "-D",
                "-U", "flash:w:" + hexPath + ":i");
    }

    /** The text the confirm dialog prints. */
    public static String display(List<String> argv) {
        return String.join(" ", argv);
    }
}
