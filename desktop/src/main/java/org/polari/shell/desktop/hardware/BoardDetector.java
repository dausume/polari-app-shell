package org.polari.shell.desktop.hardware;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Step 3: find an Arduino UNO on the serial ports. Linux only: each
 * /sys/class/tty/tty(ACM|USB)* is walked up to the USB device that
 * carries idVendor/idProduct. A UNO is VID:PID 2341:0043, 2341:0001
 * or 2a03:0043. The ids and node names stay on this machine.
 */
public final class BoardDetector {

    public static final Set<String> UNO_IDS = Set.of(
            "2341:0043", "2341:0001", "2a03:0043");

    /** One USB-serial interface variant as Polari models it (the bundle's usb-interfaces.json, from the
     *  UsbInterfaceDefinition rows): its name, a plain title, the USB ids it answers to, and whether those ids are
     *  verified against a document. The built-in default is the genuine UNO R3's ATmega16U2 bridge only. */
    public record UsbInterface(String name, String title, Set<String> ids, String verification) {
        public boolean verified() {
            return "verified".equals(verification);
        }
    }

    public static final List<UsbInterface> DEFAULT_INTERFACES = List.of(
            new UsbInterface("atmega16u2-bridge", "ATmega16U2 USB-serial bridge (the genuine UNO R3)", UNO_IDS, "verified"));

    /** The interface a port's USB id belongs to, or null. */
    public static UsbInterface interfaceOf(Port p, List<UsbInterface> known) {
        for (UsbInterface i : known) {
            if (i.ids().contains(p.vidPid())) {
                return i;
            }
        }
        return null;
    }

    /** One serial port as sysfs describes it. */
    public record Port(String device, String vid, String pid) {
        public String vidPid() {
            return vid + ":" + pid;
        }
        public boolean isUno() {
            return UNO_IDS.contains(vidPid());
        }
    }

    private BoardDetector() {}

    /** The UNOs among the ports, in the order given (the built-in interface list). */
    public static List<Port> unos(List<Port> ports) {
        return unos(ports, DEFAULT_INTERFACES);
    }

    /** The UNOs among the ports, matched against the interface variants Polari models. */
    public static List<Port> unos(List<Port> ports, List<UsbInterface> known) {
        List<Port> r = new ArrayList<>();
        for (Port p : ports) {
            if (interfaceOf(p, known) != null) {
                r.add(p);
            }
        }
        return r;
    }

    /** The sentence for the screen (the built-in interface list). */
    public static String describe(List<Port> ports) {
        return describe(ports, DEFAULT_INTERFACES);
    }

    /** The sentence for the screen: which board, through WHICH USB interface, and whether that variant is verified. */
    public static String describe(List<Port> ports, List<UsbInterface> known) {
        List<Port> unos = unos(ports, known);
        if (!unos.isEmpty()) {
            UsbInterface via = interfaceOf(unos.get(0), known);
            return "Found an Arduino UNO on " + unos.get(0).device()
                    + " through the " + via.title() + " (USB id " + unos.get(0).vidPid() + ")"
                    + (via.verified() ? "" : " - an UNVERIFIED variant: its USB id and reset wiring are not confirmed "
                    + "by a document; confirm the board before flashing")
                    + (unos.size() > 1 ? " (" + unos.size()
                    + " found - using the first)" : "") + ".";
        }
        if (ports.isEmpty()) {
            return "Refused: no serial port is present. Plug the UNO "
                    + "in with its USB cable and press Detect again.";
        }
        StringBuilder sb = new StringBuilder("Refused: ")
                .append(ports.size()).append(" serial port(s) found "
                + "but none is an Arduino UNO (");
        for (int i = 0; i < ports.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(ports.get(i).device())
                    .append(" ").append(ports.get(i).vidPid());
        }
        List<String> expected = new ArrayList<>();
        for (UsbInterface i : known) {
            expected.addAll(i.ids().stream().sorted().toList());
        }
        return sb.append("). Expected USB id ").append(String.join(", ", expected)).append(".").toString();
    }

    public static List<Port> scan() {
        return scan(Path.of("/sys/class/tty"), Path.of("/dev"));
    }

    /** sysTty is /sys/class/tty; devDir is /dev (both injectable). */
    public static List<Port> scan(Path sysTty, Path devDir) {
        List<Port> r = new ArrayList<>();
        if (!Files.isDirectory(sysTty)) {
            return r;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(
                sysTty, "tty{ACM,USB}*")) {
            for (Path tty : ds) {
                Path dev = tty.resolve("device");
                if (!Files.exists(dev)) {
                    continue;
                }
                String[] ids = usbIds(dev);
                if (ids != null) {
                    r.add(new Port(devDir.resolve(
                            tty.getFileName().toString()).toString(),
                            ids[0], ids[1]));
                }
            }
        } catch (IOException e) {
            // an unreadable sysfs is "no ports", said on screen
        }
        r.sort(java.util.Comparator.comparing(Port::device));
        return r;
    }

    private static String[] usbIds(Path dev) throws IOException {
        Path p = dev.toRealPath();
        for (int i = 0; i < 5 && p != null; i++, p = p.getParent()) {
            Path v = p.resolve("idVendor");
            Path d = p.resolve("idProduct");
            if (Files.isRegularFile(v) && Files.isRegularFile(d)) {
                return new String[] {
                        Files.readString(v).trim()
                                .toLowerCase(Locale.ROOT),
                        Files.readString(d).trim()
                                .toLowerCase(Locale.ROOT)};
            }
        }
        return null;
    }
}
