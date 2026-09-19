package org.polari.shell.desktop.pipeline;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;

/**
 * The native secret dialog (ci-11b): a Swing {@link JPasswordField},
 * because it is the one text control whose value lives in a char[]
 * the caller can zero — a JTextField would leave an immutable String
 * of the token on the heap.
 *
 * <p>Deliberately NOT a web page: a value typed into the embedded
 * browser would exist in the renderer process, in the page's DOM and
 * in whatever the page chose to do with it. This is the whole reason
 * the first-run panel is native.
 */
public final class SwingSecretPrompt implements SecretPrompt {

    private final Component parent;

    public SwingSecretPrompt(Component parent) {
        this.parent = parent;
    }

    @Override
    public char[] ask(String title, String explain) {
        if (!SwingUtilities.isEventDispatchThread()) {
            final char[][] box = new char[1][];
            try {
                SwingUtilities.invokeAndWait(
                        () -> box[0] = show(title, explain));
            } catch (Exception e) {
                return null;
            }
            return box[0];
        }
        return show(title, explain);
    }

    private char[] show(String title, String explain) {
        JPasswordField field = new JPasswordField(28);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JLabel note = new JLabel("<html><body style='width:380px'>"
                + escape(explain)
                + "<br><br><i>The value goes straight to the "
                + "command's input. It is never shown, logged, put "
                + "in a command line, or handed to the page.</i>"
                + "</body></html>");
        panel.add(note, BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(420,
                panel.getPreferredSize().height));
        int answer = JOptionPane.showConfirmDialog(parent, panel,
                title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        char[] value = field.getPassword();
        if (answer != JOptionPane.OK_OPTION || value.length == 0) {
            java.util.Arrays.fill(value, '\0');
            return null;
        }
        return value;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
