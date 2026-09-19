package org.polari.shell.desktop.pipeline;

import javax.swing.JFrame;
import javax.swing.WindowConstants;

/**
 * The bootstrap window (ci-11b): the native first-run panel in its
 * own frame. When the setup says it is ready the frame closes and
 * the normal shell (browser at the app's start route) opens — the
 * panel is never the main UI.
 */
public final class PipelineSetupWindow {

    private final JFrame frame = new JFrame("Polari Pipeline — setup");

    public void open(PipelineRunner runner, String firstStep,
                     Runnable onReady) {
        PipelineSetupPanel panel = new PipelineSetupPanel(runner,
                () -> {
                    frame.dispose();
                    onReady.run();
                });
        frame.setDefaultCloseOperation(
                WindowConstants.EXIT_ON_CLOSE);
        frame.setContentPane(panel);
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
        panel.start(firstStep);
    }
}
