package org.polari.shell.desktop.pipeline;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.polari.shell.core.pipeline.VerbSpec;

/**
 * The NATIVE first-run panel (ci-11b). It is the bootstrap, not the
 * main UI: the moment the pipeline answers, the shell switches to
 * the browser at the app's own page.
 *
 * <p>It is a RENDERER of `polari-pipeline-setup/1`. It holds no
 * pipeline logic: no step is named in this file, no check is
 * re-implemented, no verdict is computed. It reads the step the
 * launcher named, draws whatever `explain`/`checks`/`questions`/
 * `actions` the document declares, runs the verb each declares
 * through the same allowlist the page uses, and moves to the next
 * step the document names.
 */
public final class PipelineSetupPanel extends JPanel {

    /** A question/action may put this in a param value; the panel
     *  substitutes what the person typed or chose. The bash half
     *  decides WHICH parameter it lands in — not this file. */
    static final String ANSWER = "{answer}";

    private final PipelineRunner runner;
    private final Runnable onReady;

    private SetupDoc doc = new SetupDoc();
    private int index;
    private boolean busy;

    private final JLabel heading = new JLabel(" ");
    private final JLabel progress = new JLabel(" ");
    private final JPanel body = new JPanel();
    private final JTextArea log = new JTextArea(7, 80);
    private final JButton back = new JButton("Back");
    private final JButton next = new JButton("Next");
    private final JButton open = new JButton("Open the pipeline");

    public PipelineSetupPanel(PipelineRunner runner,
                              Runnable onReady) {
        super(new BorderLayout(0, 8));
        this.runner = runner;
        this.onReady = onReady;
        setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        heading.setFont(heading.getFont()
                .deriveFont(Font.BOLD, 17f));
        JPanel top = new JPanel(new BorderLayout());
        top.add(heading, BorderLayout.NORTH);
        top.add(progress, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setBorder(BorderFactory.createTitledBorder(
                "what ran, and what it said"));

        JPanel split = new JPanel(new BorderLayout(0, 8));
        split.add(scroll, BorderLayout.CENTER);
        split.add(logScroll, BorderLayout.SOUTH);
        add(split, BorderLayout.CENTER);

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        back.addActionListener(e -> move(-1));
        next.addActionListener(e -> move(1));
        open.addActionListener(e -> onReady.run());
        open.setEnabled(false);
        bar.add(back);
        bar.add(next);
        bar.add(open);
        add(bar, BorderLayout.SOUTH);
        // "Open anyway" is the shell's standing habit (ShellFrame):
        // never trap someone behind our own verdict — a person whose
        // core is up can always leave the bootstrap.
        open.setEnabled(true);
        open.setToolTipText("the setup has not said ready yet — "
                + "the page may be unreachable");
        setPreferredSize(new Dimension(920, 720));
    }

    // ---- driving -------------------------------------------------

    /** Read the step the LAUNCHER named. The bash half owns what the
     *  steps are called; this file must never learn one. */
    public void start(String firstStep) {
        if (runner.catalog().isEmpty()) {
            heading.setText("The pipeline verbs are not installed");
            append("No " + org.polari.shell.core.pipeline
                    .VerbCatalog.defaultPath() + " on this device.\n"
                    + "Install the polari-pipeline package, or run "
                    + "the setup in a terminal:\n  pol jenkins "
                    + "setup\n");
            return;
        }
        if (firstStep == null || firstStep.isBlank()) {
            heading.setText("Nothing to show yet");
            append("The launcher did not name a first setup step "
                    + "(--pipeline-setup <step>).\nRun the setup in "
                    + "a terminal:\n  pol jenkins setup\n");
            return;
        }
        load(firstStep);
    }

    private void load(String stepId) {
        run(() -> runner.run("setup-step",
                Map.of("step", stepId)), r -> {
            if (r.json == null) {
                heading.setText("The pipeline did not answer in "
                        + "the setup protocol");
                append(r.error != null ? r.error : r.output);
                return;
            }
            SetupDoc read = SetupDoc.of(r.json);
            if (!read.looksValid()) {
                heading.setText("Unrecognised setup document");
                append("protocol was '" + read.protocol
                        + "', expected " + SetupDoc.PROTOCOL + "\n");
                return;
            }
            doc = read;
            index = Math.max(0, indexOf(stepId));
            render();
        });
    }

    private int indexOf(String stepId) {
        for (int i = 0; i < doc.steps.size(); i++) {
            if (doc.steps.get(i).id.equals(stepId)) {
                return i;
            }
        }
        return 0;
    }

    private void move(int delta) {
        int to = index + delta;
        if (to < 0 || to >= doc.steps.size()) {
            return;
        }
        load(doc.steps.get(to).id);
    }

    // ---- rendering ----------------------------------------------

    private void render() {
        SetupDoc.Step step = doc.steps.get(index);
        heading.setText(step.title.isBlank()
                ? step.id : step.title);
        progress.setText("step " + (index + 1) + " of "
                + doc.steps.size()
                + (doc.total > 0 ? "   ·   " + doc.complete + " of "
                        + doc.total + " complete" : "")
                + (doc.ready ? "   ·   ready"
                        : doc.blocking.isBlank() ? ""
                                : "   ·   " + doc.blocking));
        body.removeAll();

        if (!step.explain.isBlank()) {
            body.add(wrapped(step.explain));
            body.add(Box.createVerticalStrut(8));
        }
        if (!step.where.isBlank()) {
            body.add(wrapped("Where: " + step.where));
            body.add(Box.createVerticalStrut(8));
        }
        step.checks.forEach(c -> body.add(checkRow(c)));
        if (!step.checks.isEmpty()) {
            body.add(Box.createVerticalStrut(10));
        }
        step.questions.forEach(q -> body.add(question(q)));
        if (!step.actions.isEmpty()) {
            body.add(section("What this step can do for you"));
            step.actions.forEach(a -> body.add(actionRow(a)));
        }
        if (index == doc.steps.size() - 1 && !doc.todo.isEmpty()) {
            body.add(section("Still to do, in order"));
            for (int i = 0; i < doc.todo.size(); i++) {
                body.add(wrapped((i + 1) + ". " + doc.todo.get(i)));
            }
        }
        back.setEnabled(index > 0 && !busy);
        next.setEnabled(index < doc.steps.size() - 1 && !busy);
        open.setText(doc.ready ? "Open the pipeline"
                : "Open the pipeline anyway");
        open.setToolTipText(doc.ready
                ? "the setup reports ready"
                : "the setup has not said ready yet — the page may "
                        + "be unreachable");
        body.revalidate();
        body.repaint();
    }

    private JComponent checkRow(SetupDoc.Check c) {
        String mark = switch (c.state.toLowerCase()) {
            case "ok", "pass", "true" -> "[ok]  ";
            case "warn", "warning" -> "[--]  ";
            case "fail", "failed", "false" -> "[!!]  ";
            default -> "[  ]  ";
        };
        StringBuilder sb = new StringBuilder(mark).append(c.label);
        if (!c.detail.isBlank()) {
            sb.append(" — ").append(c.detail);
        }
        if (!c.fix.isBlank()) {
            sb.append("\n        → ").append(c.fix);
        }
        return wrapped(sb.toString());
    }

    /** A question renders as its declared kind and, when it names a
     *  verb, gains an Apply that runs it. A question with no verb is
     *  shown read-only — the panel refuses to invent a way to apply
     *  an answer the pipeline did not offer one for. */
    private JComponent question(SetupDoc.Act q) {
        JPanel row = new JPanel(new BorderLayout(8, 2));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        row.add(new JLabel(q.label.isBlank() ? q.id : q.label),
                BorderLayout.NORTH);

        Supplier<String> value;
        switch (q.kind) {
            case "choice" -> {
                JComboBox<String> box =
                        new JComboBox<>(q.options.toArray(
                                new String[0]));
                if (!q.value.isBlank()) {
                    box.setSelectedItem(q.value);
                }
                row.add(box, BorderLayout.CENTER);
                value = () -> String.valueOf(box.getSelectedItem());
            }
            case "confirm" -> {
                JCheckBox cb = new JCheckBox("yes",
                        "true".equalsIgnoreCase(q.value));
                row.add(cb, BorderLayout.CENTER);
                value = () -> cb.isSelected() ? "yes" : "no";
            }
            case "checklist" -> {
                JPanel list = new JPanel();
                list.setLayout(new BoxLayout(list,
                        BoxLayout.Y_AXIS));
                List<JCheckBox> boxes = new ArrayList<>();
                for (String o : q.options) {
                    JCheckBox cb = new JCheckBox(o,
                            q.value.contains(o));
                    boxes.add(cb);
                    list.add(cb);
                }
                row.add(list, BorderLayout.CENTER);
                value = () -> {
                    List<String> on = new ArrayList<>();
                    boxes.forEach(b -> {
                        if (b.isSelected()) {
                            on.add(b.getText());
                        }
                    });
                    return String.join(",", on);
                };
            }
            case "secret" -> {
                row.add(new JLabel("the value is typed in a native "
                        + "password box — never in the page"),
                        BorderLayout.CENTER);
                value = () -> "";
            }
            default -> {
                JTextField tf = new JTextField(q.value, 28);
                row.add(tf, BorderLayout.CENTER);
                value = tf::getText;
            }
        }
        if (q.runnable()) {
            JButton apply = new JButton("secret".equals(q.kind)
                    ? "Set…" : "Apply");
            apply.addActionListener(e -> invoke(q, value.get()));
            row.add(apply, BorderLayout.EAST);
        } else {
            row.add(new JLabel("(terminal)"), BorderLayout.EAST);
        }
        return row;
    }

    private JComponent actionRow(SetupDoc.Act a) {
        JPanel row = new JPanel(new BorderLayout(8, 2));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(new JLabel(a.label.isBlank() ? a.id : a.label),
                BorderLayout.CENTER);
        JButton go = new JButton("Run");
        go.setEnabled(a.runnable() || !a.id.isBlank());
        go.addActionListener(e -> invoke(a, ""));
        row.add(go, BorderLayout.EAST);
        return row;
    }

    // ---- the two paths (the SAME ones the page uses) -------------

    private void invoke(SetupDoc.Act a, String answer) {
        String verb = a.verb;
        Map<String, String> params = new LinkedHashMap<>();
        if (verb.isBlank()) {
            // the document offered no verb: the only thing an
            // ACTION id can mean is "run this action" — which is a
            // verb in the allowlist, named by the file, not here
            verb = defaultActionVerb();
            if (verb == null) {
                append("this action needs the terminal: "
                        + a.id + "\n");
                return;
            }
            params.put(onlyPlaceholder(verb), a.id);
        } else {
            a.params.forEach((k, v) -> params.put(k,
                    ANSWER.equals(v) ? answer : v));
        }
        boolean priv = runner.catalog().find(verb)
                .map(v -> v.privileged).orElse(false);
        final String id = verb;
        run(() -> priv ? runner.runPrivileged(id, params)
                        : runner.run(id, params),
            r -> {
                append("$ " + id + " " + params + "\n"
                        + (r.error != null ? r.error : r.output)
                        + "\n");
                load(doc.steps.get(index).id);
            });
    }

    /** The one verb whose single parameter carries an action id —
     *  found by SHAPE in the allowlist, so no verb name is written
     *  into this file either. */
    private String defaultActionVerb() {
        for (String id : runner.catalog().ids()) {
            VerbSpec v = runner.catalog().find(id).orElseThrow();
            if (!v.privileged && v.placeholders().size() == 1
                    && v.argv.contains("--run")) {
                return id;
            }
        }
        return null;
    }

    private String onlyPlaceholder(String verb) {
        return runner.catalog().find(verb).orElseThrow()
                .placeholders().get(0);
    }

    // ---- plumbing ------------------------------------------------

    private void run(Supplier<PipelineRunner.Outcome> work,
                     java.util.function.Consumer<
                             PipelineRunner.Outcome> then) {
        busy = true;
        back.setEnabled(false);
        next.setEnabled(false);
        new Thread(() -> {
            PipelineRunner.Outcome r = work.get();
            SwingUtilities.invokeLater(() -> {
                busy = false;
                then.accept(r);
                if (!doc.steps.isEmpty()) {
                    back.setEnabled(index > 0);
                    next.setEnabled(index < doc.steps.size() - 1);
                }
            });
        }, "polari-pipeline-setup").start();
    }

    private void append(String s) {
        log.append(s == null ? "" : s);
        log.setCaretPosition(log.getDocument().getLength());
    }

    private static JComponent wrapped(String text) {
        JTextArea a = new JTextArea(text);
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setOpaque(false);
        a.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
        a.setAlignmentX(Component.LEFT_ALIGNMENT);
        return a;
    }

    private static JComponent section(String title) {
        JLabel l = new JLabel(title);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        l.setBorder(BorderFactory.createEmptyBorder(12, 0, 4, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }
}
