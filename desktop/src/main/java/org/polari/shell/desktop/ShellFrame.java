package org.polari.shell.desktop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.KeyboardFocusManager;
import java.net.URI;
import java.util.List;
import javax.swing.JFrame;
import javax.swing.WindowConstants;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import org.polari.shell.core.bridge.BridgeRouter;
import org.polari.shell.core.net.ReachabilityProbe;
import org.polari.shell.core.registry.InstanceRegistry;
import org.polari.shell.core.registry.RegisteredInstance;
import org.polari.shell.core.tls.InstanceTrust;

/**
 * The host window (plan B1): a Swing JFrame whose NORTH bar is
 * JavaFX chrome in a JFXPanel (instance switcher, probe status,
 * advisory + Retry / Open anyway) and whose CENTER is the windowed
 * JCEF component. "JavaFX everywhere" holds at the UI-code level;
 * the top-level container is Swing because that is the
 * configuration JCEF is actually stable in.
 */
final class ShellFrame {

    private final InstanceRegistry registry;
    private final List<RegisteredInstance> all;
    private final JFrame frame =
            new JFrame("Polari");
    private RegisteredInstance current;
    private ReachabilityProbe.Result probe;
    private CefBrowserHost cef;
    private Label status;

    ShellFrame(InstanceRegistry registry,
               RegisteredInstance current,
               ReachabilityProbe.Result probe,
               List<RegisteredInstance> all) {
        this.registry = registry;
        this.current = current;
        this.probe = probe;
        this.all = all;
    }

    void open() {
        frame.setDefaultCloseOperation(
                WindowConstants.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.setSize(1280, 860);
        frame.setTitle(title());

        JFXPanel chrome = new JFXPanel();
        frame.add(chrome, BorderLayout.NORTH);
        Platform.runLater(() -> chrome.setScene(buildChrome()));

        attachBrowser();
        frame.setVisible(true);
    }

    private String title() {
        return current.config.displayName.isBlank()
                ? "Polari — " + current.config.id
                : current.config.displayName;
    }

    private Scene buildChrome() {
        ComboBox<String> picker = new ComboBox<>();
        all.forEach(r -> picker.getItems().add(r.config.id));
        picker.getSelectionModel().select(current.config.id);
        picker.setOnAction(e -> switchInstance(
                picker.getValue()));

        status = new Label(statusText());
        Button retry = new Button("Retry");
        retry.setOnAction(e -> reprobe());
        Button openAnyway = new Button("Open anyway");
        openAnyway.setOnAction(e -> loadWeb());

        HBox bar = new HBox(10, picker, status, retry, openAnyway);
        bar.setPadding(new Insets(6));
        HBox.setHgrow(status, Priority.ALWAYS);
        return new Scene(bar);
    }

    private String statusText() {
        if (ReachabilityProbe.REACHABLE.equals(probe.state())) {
            return "● " + current.config.webUrl;
        }
        return ReachabilityProbe.advisory(current.config, probe);
    }

    private void attachBrowser() {
        try {
            BridgeRouter bridge = buildBridge();
            cef = new CefBrowserHost(current.config, bridge);
            String target =
                    ReachabilityProbe.REACHABLE.equals(probe.state())
                            ? startUrl() : advisoryDataUrl();
            frame.add(cef.open(target), BorderLayout.CENTER);
            // JCEF steals focus on load (known quirk) — keep Tab
            // traversal alive in the chrome.
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .clearGlobalFocusOwner();
        } catch (Throwable t) {
            // Honest degrade: no JCEF (first-run download refused,
            // unsupported arch...) -> system browser + a plain
            // frame, never a silent white window.
            System.err.println("[cef] unavailable: " + t);
            try {
                Desktop.getDesktop().browse(
                        URI.create(current.config.webUrl));
            } catch (Exception ignored) {
                // headless — the advisory already went to stderr
            }
        }
    }

    private String startUrl() {
        return current.config.webUrl;
    }

    private void loadWeb() {
        if (cef != null) {
            cef.load(startUrl());
        }
    }

    private void reprobe() {
        new Thread(() -> {
            try {
                probe = ReachabilityProbe.probe(current.config,
                        InstanceTrust.sslContext(
                                current.config.tls.caPem));
            } catch (Exception e) {
                probe = new ReachabilityProbe.Result(
                        ReachabilityProbe.UNKNOWN, e.toString());
            }
            Platform.runLater(() ->
                    status.setText(statusText()));
            if (ReachabilityProbe.REACHABLE.equals(
                    probe.state())) {
                loadWeb();
            }
        }, "polari-reprobe").start();
    }

    private void switchInstance(String id) {
        registry.get(id).ifPresent(next -> {
            current = next;
            registry.markUsed(id);
            try {
                registry.save();
            } catch (Exception ignored) {
                // registry persistence failing must not stop the
                // switch; conflicts surface on next launch
            }
            if (cef != null) {
                cef.dispose();
            }
            frame.setTitle(title());
            reprobe();
            attachBrowser();
            frame.revalidate();
        });
    }

    private BridgeRouter buildBridge() {
        BridgeRouter bridge = new BridgeRouter();
        bridge.on("shell.info", p -> {
            JsonObject o = new JsonObject();
            o.addProperty("shellVersion", "0.1.0");
            o.addProperty("platform", "desktop-linux-x64");
            o.addProperty("instanceId", current.config.id);
            return o;
        });
        bridge.on("shell.instance.list", p -> {
            JsonArray arr = new JsonArray();
            all.forEach(r -> {
                JsonObject o = new JsonObject();
                o.addProperty("id", r.config.id);
                o.addProperty("displayName",
                        r.config.displayName);
                JsonObject reach = new JsonObject();
                reach.addProperty("state", r.lastProbe.state);
                reach.addProperty("at", r.lastProbe.at);
                reach.addProperty("evidence",
                        r.lastProbe.evidence);
                o.add("reachability", reach);
                arr.add(o);
            });
            JsonObject out = new JsonObject();
            out.add("instances", arr);
            return out;
        });
        bridge.on("shell.instance.switch", p -> {
            String id = p.has("id") ? p.get("id").getAsString() : "";
            javax.swing.SwingUtilities.invokeLater(
                    () -> switchInstance(id));
            JsonObject o = new JsonObject();
            o.addProperty("switched", true);
            return o;
        });
        bridge.on("shell.reachability.get", p -> {
            JsonObject o = new JsonObject();
            o.addProperty("state", probe.state());
            o.addProperty("evidence", probe.evidence());
            o.addProperty("advisory", ReachabilityProbe.advisory(
                    current.config, probe));
            return o;
        });
        bridge.on("shell.open.external", p -> {
            JsonObject o = new JsonObject();
            try {
                Desktop.getDesktop().browse(URI.create(
                        p.get("url").getAsString()));
                o.addProperty("opened", true);
            } catch (Exception e) {
                o.addProperty("opened", false);
            }
            return o;
        });
        return bridge;
    }

    private String advisoryDataUrl() {
        String html = "";
        try (var in = ShellFrame.class.getResourceAsStream(
                "/shellui/advisory.html")) {
            html = new String(in.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            html = "<h1>{{HEADLINE}}</h1><p>{{EVIDENCE}}</p>";
        }
        html = html.replace("{{TITLE}}", title())
                .replace("{{STATE}}", probe.state())
                .replace("{{HEADLINE}}", ReachabilityProbe.advisory(
                        current.config, probe))
                .replace("{{EVIDENCE}}", probe.evidence());
        return "data:text/html;base64," + java.util.Base64
                .getEncoder().encodeToString(html.getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
    }
}
