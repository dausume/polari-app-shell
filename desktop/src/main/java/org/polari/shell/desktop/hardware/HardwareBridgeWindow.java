package org.polari.shell.desktop.hardware;

import javax.net.ssl.SSLContext;
import javax.swing.JFrame;
import javax.swing.WindowConstants;

/**
 * The Hardware Bridge App's own window (the PipelineSetupWindow
 * idiom): the native panel is the main UI, and the browser stays one
 * button away for the Polari pages.
 */
public final class HardwareBridgeWindow {

    private final JFrame frame;

    public HardwareBridgeWindow(String title) {
        frame = new JFrame(title == null || title.isBlank()
                ? "Hardware Bridge App" : title);
    }

    public void open(String apiBase, SSLContext trust,
                     Runnable openPolariPages) {
        HardwareBridgePanel panel = new HardwareBridgePanel(apiBase,
                trust, openPolariPages);
        frame.setDefaultCloseOperation(
                WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                panel.stopBridge();
                frame.dispose();
                System.exit(0);
            }
        });
        frame.setContentPane(panel);
        frame.setSize(980, 640);
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }
}
