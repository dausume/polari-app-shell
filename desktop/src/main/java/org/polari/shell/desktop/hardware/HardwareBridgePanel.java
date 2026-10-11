package org.polari.shell.desktop.hardware;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Callable;
import javax.net.ssl.SSLContext;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The six steps of the light demo's Hardware Bridge App, in order:
 * open bundle, gate, detect board, flash, host bridge, light. Every
 * refusal and piece of advice is a sentence on screen. The only
 * outbound calls are the bundle fetch, the LightState GET/PUT and the
 * bridge's own gRPC frames; this machine's name, user, paths, ports
 * and serials are never sent to Polari.
 */
public final class HardwareBridgePanel extends JPanel {

    private static final String SOLUTION = "uno-light";

    private final String apiBase;
    private final SSLContext trust;
    private final Runnable openPolariPages;
    private final CardLayout cards = new CardLayout();
    private final JPanel right = new JPanel(cards);
    private final DefaultListModel<String> stepModel =
            new DefaultListModel<>();
    private final JList<String> steps = new JList<>(stepModel);
    private final JLabel banner = new JLabel(" ");

    private Bundle bundle;
    private boolean gatePassed;
    private BoardDetector.Port board;
    private Process bridge;
    private final ArrayDeque<String> tail = new ArrayDeque<>();

    private final JTextArea openOut = area(6);
    private final JTextArea gateOut = area(10);
    private final JTextArea detectOut = area(4);
    private final JTextArea flashOut = area(14);
    private final JButton flashBtn = new JButton("Flash...");
    private final JTextField target = new JTextField(28);
    private final JTextArea bridgeOut = area(14);
    private final JLabel lightOut = new JLabel(
            "Start the bridge to see the light.");
    private Timer poll;

    public HardwareBridgePanel(String apiBase, SSLContext trust,
                               Runnable openPolariPages) {
        super(new BorderLayout(8, 8));
        this.apiBase = apiBase;
        this.trust = trust;
        this.openPolariPages = openPolariPages;
        setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        for (String s : Bundle.DEFAULT_STEPS) {
            stepModel.addElement(s);
        }
        steps.setFixedCellWidth(170);
        steps.setSelectedIndex(0);
        steps.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                cards.show(right, "s" + steps.getSelectedIndex());
            }
        });
        add(new JScrollPane(steps), BorderLayout.WEST);

        right.add(openCard(), "s0");
        right.add(gateCard(), "s1");
        right.add(detectCard(), "s2");
        right.add(flashCard(), "s3");
        right.add(bridgeCard(), "s4");
        right.add(lightCard(), "s5");
        add(right, BorderLayout.CENTER);

        banner.setFont(banner.getFont().deriveFont(Font.BOLD));
        JPanel south = new JPanel(new BorderLayout());
        south.add(banner, BorderLayout.CENTER);
        JButton pages = new JButton("Open Polari pages");
        pages.addActionListener(e -> openPolariPages.run());
        south.add(pages, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);
        flashBtn.setEnabled(false);
        say("Step 1: open a demo bundle, or fetch the latest from "
                + "Polari.");
    }

    // ---- step 1 -------------------------------------------------

    private JPanel openCard() {
        JButton file = new JButton("Open bundle...");
        file.addActionListener(e -> chooseBundle());
        JButton fetch = new JButton("Fetch from Polari");
        fetch.addActionListener(e -> background("Fetching",
                () -> BundleSource.fetch(apiBase, SOLUTION, trust),
                b -> bundleOpened((Bundle) b)));
        return card("Open the demo bundle (a tar.gz or a folder), or "
                + "fetch the latest one Polari has.",
                List.of(file, fetch), openOut);
    }

    private void chooseBundle() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path p = fc.getSelectedFile().toPath();
        background("Opening", () -> BundleSource.open(p),
                b -> bundleOpened((Bundle) b));
    }

    private void bundleOpened(Bundle b) {
        bundle = b;
        List<String> names = b.stepNames();
        for (int i = 0; i < names.size(); i++) {
            stepModel.set(i, names.get(i));
        }
        openOut.setText("Opened " + b.dir.getFileName() + ".\n"
                + (b.hasFirmware() ? "" : "It has no firmware.hex.\n")
                + (java.nio.file.Files.isRegularFile(b.jar()) ? ""
                : "It has no polari-hw-bridge.jar: the bridge step "
                + "cannot run.\n"));
        target.setText(BridgeProperties.grpcTarget(
                b.propertiesText()));
        runGate();
        steps.setSelectedIndex(1);
    }

    // ---- step 2 -------------------------------------------------

    private JPanel gateCard() {
        JButton again = new JButton("Check again");
        again.addActionListener(e -> runGate());
        return card("The gate: firmware.hex must match the hash in "
                + "polari-install.json. Only a mismatch stops you; "
                + "everything else is advice.", List.of(again),
                gateOut);
    }

    private void runGate() {
        if (bundle == null) {
            gateOut.setText("Open a bundle first.");
            return;
        }
        try {
            BundleGate.Verdict v = BundleGate.check(bundle.hex(),
                    bundle.installManifest());
            gatePassed = v.passed();
            StringBuilder sb = new StringBuilder(v.sentence())
                    .append('\n');
            v.advice().forEach(a -> sb.append("Advice: ").append(a)
                    .append('\n'));
            String head = bundle.readmeHead(6);
            sb.append(head.isEmpty() ? "\n(No README in the bundle - "
                    + "advice only.)" : "\nREADME:\n" + head);
            gateOut.setText(sb.toString());
            say(v.sentence());
        } catch (Exception e) {
            gatePassed = false;
            gateOut.setText("Could not read firmware.hex: "
                    + e.getMessage());
        }
        updateFlash();
    }

    // ---- step 3 -------------------------------------------------

    private JPanel detectCard() {
        JButton d = new JButton("Detect board");
        d.addActionListener(e -> detect());
        return card("Plug the Arduino UNO in with its USB cable, "
                + "then detect it.", List.of(d), detectOut);
    }

    private void detect() {
        List<BoardDetector.Port> ports = BoardDetector.scan();
        String sentence = BoardDetector.describe(ports);
        List<BoardDetector.Port> unos = BoardDetector.unos(ports);
        board = unos.isEmpty() ? null : unos.get(0);
        detectOut.setText(sentence);
        say(sentence);
        updateFlash();
    }

    // ---- step 4 -------------------------------------------------

    private JPanel flashCard() {
        flashBtn.addActionListener(e -> flash());
        return card("Flash puts firmware.hex on the board with "
                + "avrdude and reads it back to verify. It stays "
                + "greyed out until the gate has passed and a board "
                + "is detected.", List.of(flashBtn), flashOut);
    }

    private void updateFlash() {
        flashBtn.setEnabled(gatePassed && board != null);
    }

    private void flash() {
        List<String> argv;
        try {
            argv = AvrdudeCommand.build(board.device(),
                    bundle.hex().toAbsolutePath().toString());
        } catch (IllegalArgumentException ex) {
            flashOut.setText("Refused: " + ex.getMessage());
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this,
                "This will run exactly:\n\n"
                + AvrdudeCommand.display(argv)
                + "\n\nand overwrite the program on the board. "
                + "Continue?", "Confirm flash",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) {
            return;
        }
        flashBtn.setEnabled(false);
        flashOut.setText("$ " + AvrdudeCommand.display(argv) + "\n");
        background("Flashing", () -> org.polari.shell.desktop
                .HostProcess.run(argv, 180), r -> {
            var res = (org.polari.shell.desktop.HostProcess.Result) r;
            flashOut.append(res.output + "\n");
            String s = res.exitCode == 0
                    ? "Flashed and verified. Next: host the bridge."
                    : "avrdude exited " + res.exitCode + "."
                    + (res.output.contains("ermission denied")
                    ? " The port is not writable: add your user to "
                    + "the dialout group, then sign in again." : "");
            flashOut.append(s + "\n");
            say(s);
            updateFlash();
            if (res.exitCode == 0) {
                steps.setSelectedIndex(4);
            }
        });
    }

    // ---- step 5 -------------------------------------------------

    private JPanel bridgeCard() {
        JButton start = new JButton("Start bridge");
        start.addActionListener(e -> startBridge());
        JButton stop = new JButton("Stop");
        stop.addActionListener(e -> stopBridge());
        JPanel p = card("The bridge carries frames between the board "
                + "and Polari. Edit the Polari address if needed.",
                List.of(new JLabel("grpc.target"), target, start,
                        stop), bridgeOut);
        return p;
    }

    private void startBridge() {
        if (bundle == null || !java.nio.file.Files.isRegularFile(
                bundle.jar())) {
            say("Refused: the bundle has no polari-hw-bridge.jar.");
            return;
        }
        if (board == null) {
            say("Refused: detect the board first (the bridge needs "
                    + "its serial port).");
            return;
        }
        if (bridge != null && bridge.isAlive()) {
            say("The bridge is already running.");
            return;
        }
        try {
            BridgeProperties.write(bundle.properties(), board.device(),
                    target.getText().trim());
            ProcessBuilder pb = new ProcessBuilder(
                    javaExe(), "-jar", "polari-hw-bridge.jar",
                    "bridge.properties");
            pb.directory(bundle.dir.toFile());
            pb.redirectErrorStream(true);
            bridge = pb.start();
            Process p = bridge;
            Runtime.getRuntime().addShutdownHook(
                    new Thread(p::destroy));
            Thread t = new Thread(() -> pump(p), "hw-bridge-out");
            t.setDaemon(true);
            t.start();
            say("Bridge started. Watch the Light step.");
            startPolling();
        } catch (Exception e) {
            say("Could not start the bridge: " + e.getMessage());
        }
    }

    private static String javaExe() {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String d : path.split(File.pathSeparator)) {
                File f = new File(d, "java");
                if (f.canExecute()) {
                    return f.getPath();
                }
            }
        }
        return Path.of(System.getProperty("java.home"), "bin", "java")
                .toString();
    }

    private void pump(Process p) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(),
                        StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                final String line = l;
                SwingUtilities.invokeLater(() -> {
                    tail.addLast(line);
                    while (tail.size() > 200) {
                        tail.removeFirst();
                    }
                    bridgeOut.setText(String.join("\n", tail));
                });
            }
        } catch (Exception ignored) {
            // the process ended
        }
        SwingUtilities.invokeLater(() -> say("The bridge stopped."));
    }

    public void stopBridge() {
        if (bridge != null && bridge.isAlive()) {
            bridge.destroy();
            say("Bridge stopped.");
        }
    }

    // ---- step 6 -------------------------------------------------

    private JPanel lightCard() {
        JButton toggle = new JButton("Toggle light");
        toggle.addActionListener(e -> background("Toggling",
                () -> new LightClient(apiBase, trust).toggle(),
                r -> say("Asked Polari to switch the light; the "
                        + "board answers through the bridge.")));
        lightOut.setFont(lightOut.getFont().deriveFont(15f));
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.add(new JLabel("The light, as Polari sees it. The toggle "
                + "goes through Polari, never straight to the board."),
                BorderLayout.NORTH);
        p.add(lightOut, BorderLayout.CENTER);
        JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT));
        b.add(toggle);
        p.add(b, BorderLayout.SOUTH);
        return p;
    }

    private void startPolling() {
        if (poll != null) {
            return;
        }
        poll = new Timer(2000, e -> {
            Thread t = new Thread(() -> {
                String text;
                try {
                    text = new LightClient(apiBase, trust).read()
                            .sentence();
                } catch (Exception ex) {
                    text = ex.getMessage();
                }
                final String shown = text;
                SwingUtilities.invokeLater(() ->
                        lightOut.setText(shown));
            }, "hw-light-poll");
            t.setDaemon(true);
            t.start();
        });
        poll.start();
    }

    // ---- helpers ------------------------------------------------

    private void say(String s) {
        banner.setText(s);
    }

    private static JTextArea area(int rows) {
        JTextArea a = new JTextArea(rows, 60);
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return a;
    }

    private static JPanel card(String explain,
            List<? extends java.awt.Component> controls,
            JTextArea out) {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        JPanel top = new JPanel(new BorderLayout(4, 4));
        JLabel l = new JLabel("<html>" + explain + "</html>");
        top.add(l, BorderLayout.NORTH);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.forEach(row::add);
        top.add(row, BorderLayout.SOUTH);
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(out), BorderLayout.CENTER);
        return p;
    }

    /** Run off the UI thread; deliver the result (or the failure as
     *  a sentence) on it. */
    private void background(String what, Callable<Object> job,
                            java.util.function.Consumer<Object> done) {
        say(what + "...");
        Thread t = new Thread(() -> {
            try {
                Object r = job.call();
                SwingUtilities.invokeLater(() -> done.accept(r));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    String m = e.getMessage() == null
                            ? e.toString() : e.getMessage();
                    say(what + " failed: " + m);
                    openOut.setText(what + " failed: " + m);
                });
            }
        }, "hw-bg");
        t.setDaemon(true);
        t.start();
    }
}
