package org.polari.shell.desktop.hardware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HardwareBridgeTest {

    @TempDir Path tmp;

    private Path hex(String body) throws Exception {
        Path h = tmp.resolve("firmware.hex");
        Files.writeString(h, body, StandardCharsets.UTF_8);
        return h;
    }

    // ---- the gate ----

    @Test
    void gatePassesWhenShaEqual() throws Exception {
        Path h = hex(":00000001FF\n");
        JsonObject m = new JsonObject();
        m.addProperty("hex_sha256", BundleGate.sha256(h).toUpperCase());
        BundleGate.Verdict v = BundleGate.check(h, m);
        assertTrue(v.passed(), v.sentence());
        assertTrue(v.advice().isEmpty());
    }

    @Test
    void gateRefusesMismatchByName() throws Exception {
        Path h = hex(":00000001FF\n");
        JsonObject m = new JsonObject();
        m.addProperty("hex_sha256", "0".repeat(64));
        BundleGate.Verdict v = BundleGate.check(h, m);
        assertFalse(v.passed());
        assertTrue(v.sentence().contains(BundleGate.sha256(h)));
        assertTrue(v.sentence().contains("0".repeat(64)));
    }

    @Test
    void gateOnlyAdvisesWhenManifestMissingOrSilent() throws Exception {
        Path h = hex("x");
        assertTrue(BundleGate.check(h, null).passed());
        assertEquals(1, BundleGate.check(h, new JsonObject())
                .advice().size());
    }

    @Test
    void gateRefusesNoHex() throws Exception {
        assertFalse(BundleGate.check(tmp.resolve("nope.hex"), null)
                .passed());
    }

    // ---- the VID:PID matcher ----

    @Test
    void matcherFixtureList() {
        List<BoardDetector.Port> ports = List.of(
                new BoardDetector.Port("/dev/ttyUSB0", "1a86", "7523"),
                new BoardDetector.Port("/dev/ttyACM0", "2341", "0043"),
                new BoardDetector.Port("/dev/ttyACM1", "2341", "0001"),
                new BoardDetector.Port("/dev/ttyACM2", "2a03", "0043"),
                new BoardDetector.Port("/dev/ttyACM3", "2341", "0042"));
        List<BoardDetector.Port> unos = BoardDetector.unos(ports);
        assertEquals(List.of("/dev/ttyACM0", "/dev/ttyACM1",
                "/dev/ttyACM2"),
                unos.stream().map(BoardDetector.Port::device).toList());
        assertTrue(BoardDetector.describe(ports).startsWith(
                "Found an Arduino UNO on /dev/ttyACM0"));
    }

    @Test
    void matcherRefusesByName() {
        assertTrue(BoardDetector.describe(List.of()).startsWith(
                "Refused: no serial port"));
        String s = BoardDetector.describe(List.of(
                new BoardDetector.Port("/dev/ttyUSB0", "1a86", "7523")));
        assertTrue(s.startsWith("Refused:") && s.contains("1a86:7523"));
    }

    @Test
    void sysfsScanReadsIdsFromParentDevice() throws Exception {
        Path usb = Files.createDirectories(tmp.resolve(
                "devices/usb1/1-1"));
        Files.writeString(usb.resolve("idVendor"), "2341\n");
        Files.writeString(usb.resolve("idProduct"), "0043\n");
        Path iface = Files.createDirectories(usb.resolve("1-1:1.0"));
        Path tty = Files.createDirectories(tmp.resolve(
                "class/tty/ttyACM0"));
        Files.createSymbolicLink(tty.resolve("device"), iface);
        Files.createDirectories(tmp.resolve("class/tty/tty0"));
        List<BoardDetector.Port> ports = BoardDetector.scan(
                tmp.resolve("class/tty"), Path.of("/dev"));
        assertEquals(1, ports.size());
        assertEquals("/dev/ttyACM0", ports.get(0).device());
        assertTrue(ports.get(0).isUno());
    }

    // ---- the avrdude argv ----

    @Test
    void avrdudeArgvExact() {
        List<String> a = AvrdudeCommand.build("/dev/ttyACM0",
                "/tmp/b/firmware.hex");
        assertEquals(List.of("avrdude", "-c", "arduino", "-p",
                "atmega328p", "-P", "/dev/ttyACM0", "-b", "115200",
                "-D", "-U", "flash:w:/tmp/b/firmware.hex:i"), a);
        assertFalse(a.contains("-V"));
    }

    @Test
    void avrdudeArgvRefusesOddInput() {
        assertThrows(IllegalArgumentException.class, () ->
                AvrdudeCommand.build("ttyACM0", "/x/firmware.hex"));
        assertThrows(IllegalArgumentException.class, () ->
                AvrdudeCommand.build("/dev/ttyACM0 -V", "/x/f.hex"));
        assertThrows(IllegalArgumentException.class, () ->
                AvrdudeCommand.build("/dev/ttyACM0", "/x:y/f.hex"));
    }

    // ---- bridge.properties ----

    @Test
    void propertiesWriterKeepsBundleLinesAndSetsKeys() throws Exception {
        Path f = tmp.resolve("bridge.properties");
        Files.writeString(f, "# generated\nbaud=115200\n"
                + "serial.device=/dev/ttyACM9\ngrpc.enabled=false\n"
                + "grpc.target=host.example:3002\ndevice.id=1\n");
        BridgeProperties.write(f, "/dev/ttyACM0", "polari.test:3002");
        String t = Files.readString(f);
        assertTrue(t.startsWith("# generated\nbaud=115200\n"));
        assertTrue(t.contains("serial.device=/dev/ttyACM0\n"));
        assertTrue(t.contains("grpc.enabled=true\n"));
        assertTrue(t.contains("grpc.target=polari.test:3002\n"));
        assertTrue(t.contains("device.id=1\n"));
        assertTrue(t.contains("source=serial\n"));
        assertFalse(t.contains("ttyACM9"));
        assertEquals(1, t.split("grpc.target=", -1).length - 1);
    }

    @Test
    void propertiesWriterFromNothing() {
        assertEquals("source=serial\nserial.device=/dev/ttyACM0\n"
                + "grpc.enabled=true\ngrpc.target=a:1\n",
                BridgeProperties.render("", "/dev/ttyACM0", "a:1"));
        assertEquals("a:1", BridgeProperties.grpcTarget(
                "x=1\ngrpc.target=a:1\n"));
    }

    // ---- bundle / step names / light / exports ----

    @Test
    void stepNamesFromDefinitionElseDefault() {
        JsonObject d = JsonParser.parseString("{\"states\":["
                + "{\"name\":\"a\"},{\"title\":\"b\"},\"c\","
                + "{\"id\":\"d\"},{\"label\":\"e\"},{\"name\":\"f\"}]}")
                .getAsJsonObject();
        assertEquals(List.of("a", "b", "c", "d", "e", "f"),
                Bundle.stepNamesFrom(d));
        assertEquals(Bundle.DEFAULT_STEPS, Bundle.stepNamesFrom(
                new JsonObject()));
    }

    @Test
    void latestDemoBundlePicksNewestForSolution() {
        String j = "{\"rows\":["
                + "{\"form\":\"demo-bundle\",\"solution\":\"uno-light\","
                + "\"created_at\":\"2026-10-10\",\"download_url\":\"/a\"},"
                + "{\"form\":\"demo-bundle\",\"solution\":\"uno-light\","
                + "\"created_at\":\"2026-10-11\",\"download_url\":\"/b\"},"
                + "{\"form\":\"source-dir\",\"solution\":\"uno-light\","
                + "\"created_at\":\"2026-10-12\",\"download_url\":\"/c\"}]}";
        assertEquals("/b", BundleSource.latest(j, "uno-light")
                .get("download_url").getAsString());
        assertEquals(null, BundleSource.latest(j, "other"));
    }

    @Test
    void lightStateParsesAndPutBodyIsCrude() {
        LightClient.State s = LightClient.parse("[{\"class\":"
                + "\"LightState\",\"data\":[{\"id\":\"wuV1\","
                + "\"name\":\"uno-light\",\"seq\":7,\"led_on\":true,"
                + "\"toggles\":3,\"source\":\"serial\","
                + "\"status\":\"live\"}]}]");
        assertNotNull(s);
        assertTrue(s.ledOn());
        assertEquals(7, s.frames());
        assertEquals("wuV1", s.id());
        String body = LightClient.putBody("B", "wuV1", false);
        assertTrue(body.contains("name=\"polariId\"\r\n\r\nwuV1"));
        assertTrue(body.contains("{\"led_on\": false}"));
    }

    private static String state(String name, String label, String p) {
        return "{\"stateName\":\"" + name + "\","
                + "\"boundObjectFieldValues\":{\"displayName\":\""
                + label + "\",\"purpose\":\"" + p + "\"}}";
    }

    @Test
    void stepsFromSolutionRowInOrderWithPurposes() {
        String def = "{\"stateInstances\":["
                + state("open-bundle", "1. Open the demo bundle", "Opens it.")
                + "," + state("gate", "2. Check the firmware", "Compares.")
                + "," + state("detect-board", "3. Find the board", "Looks.")
                + "," + state("flash", "4. Flash the firmware", "Writes.")
                + "," + state("host-bridge", "5. Run the bridge", "Starts.")
                + "," + state("sync-state", "6. Keep the light in step", "Syncs.")
                + "]}";
        JsonObject root = new JsonObject();
        root.addProperty("schema", "polari-app-definition/1");
        com.google.gson.JsonArray sols = new com.google.gson.JsonArray();
        JsonObject other = new JsonObject();
        other.addProperty("name", "uno-light");
        sols.add(other);
        JsonObject row = new JsonObject();
        row.addProperty("name", "uno-light-app");
        row.addProperty("category", "hardware-app");
        row.addProperty("definition", def); // served as a JSON string
        sols.add(row);
        root.add("solutions", sols);
        List<Bundle.Step> st = Bundle.stepsFrom(root);
        assertEquals(List.of("Open the demo bundle",
                "Check the firmware", "Find the board",
                "Flash the firmware", "Run the bridge",
                "Keep the light in step"),
                st.stream().map(Bundle.Step::name).toList());
        assertEquals("Opens it.", st.get(0).purpose());
        assertEquals("Syncs.", st.get(5).purpose());
        // definition served as an object works too
        row.add("definition", JsonParser.parseString(def));
        assertEquals(6, Bundle.stepsFrom(root).size());
    }

    @Test
    void absentRowFallsBackToTopLevelThenBuiltIn() {
        JsonObject noRow = JsonParser.parseString(
                "{\"solutions\":[{\"name\":\"x\"}]}").getAsJsonObject();
        assertEquals(Bundle.DEFAULT_STEPS, Bundle.stepNamesFrom(noRow));
        JsonObject emptyStates = JsonParser.parseString("{\"solutions\":"
                + "[{\"name\":\"uno-light-app\",\"definition\":\"{}\"}]}")
                .getAsJsonObject();
        assertEquals(Bundle.DEFAULT_STEPS,
                Bundle.stepNamesFrom(emptyStates));
    }

    @org.junit.jupiter.api.Test
    void detectorMatchesModelledVariantsAndSaysWhenUnverified() {
        com.google.gson.JsonObject doc = com.google.gson.JsonParser.parseString(
                "{\"interfaces\": [{\"name\": \"atmega16u2-bridge\", \"title\": \"ATmega16U2 bridge\","
                + " \"ids\": [\"2341:0043\"], \"verification\": \"verified\"},"
                + " {\"name\": \"ch340-bridge\", \"title\": \"CH340 bridge\", \"ids\": [\"1A86:7523\"],"
                + " \"verification\": \"unverified\"}]}").getAsJsonObject();
        java.util.List<BoardDetector.UsbInterface> known = Bundle.usbInterfacesFrom(doc);
        java.util.List<BoardDetector.Port> clone = java.util.List.of(new BoardDetector.Port("/dev/ttyUSB0", "1a86", "7523"));
        org.junit.jupiter.api.Assertions.assertEquals(1, BoardDetector.unos(clone, known).size());
        String s = BoardDetector.describe(clone, known);
        org.junit.jupiter.api.Assertions.assertTrue(s.contains("CH340 bridge") && s.contains("UNVERIFIED"), s);
        // the built-in default still refuses the clone (unchanged behaviour without a bundle)
        org.junit.jupiter.api.Assertions.assertTrue(BoardDetector.unos(clone).isEmpty());
        // an ESP32 dev board's CP2102 is refused by name under the modelled list too
        java.util.List<BoardDetector.Port> esp = java.util.List.of(new BoardDetector.Port("/dev/ttyUSB1", "10c4", "ea60"));
        org.junit.jupiter.api.Assertions.assertTrue(BoardDetector.describe(esp, known).startsWith("Refused"));
        // an old bundle with no usb-interfaces.json falls back to the default
        org.junit.jupiter.api.Assertions.assertEquals(BoardDetector.DEFAULT_INTERFACES, Bundle.usbInterfacesFrom(null));
    }
}
