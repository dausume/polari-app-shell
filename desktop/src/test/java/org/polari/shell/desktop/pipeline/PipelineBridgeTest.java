package org.polari.shell.desktop.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.polari.shell.core.bridge.BridgeRouter;
import org.polari.shell.core.pipeline.VerbCatalog;

/**
 * ci-11b: the reply SHAPE is a contract with the Polari page, and
 * every refusal below happens before a process is started — so this
 * runs headless, starts nothing, and needs no allowlist on disk.
 */
class PipelineBridgeTest {

    private static final String VERBS = """
        {"protocol":"polari-pipeline-shell/1","verbs":{
          "doctor":{"argv":["pol","jenkins","doctor"],
                    "privileged":false,"label":"doctor"},
          "init-device":{"argv":["pol","jenkins","init-device"],
                    "privileged":true,"label":"init"},
          "setup-step":{"argv":["pol","jenkins","setup","--json",
                    "--step","{step}"],"privileged":false,
                    "params":{"step":"^[a-z-]+$"},"label":"step"}
        }}""";

    private static BridgeRouter router() {
        BridgeRouter r = new BridgeRouter();
        new PipelineBridge(new PipelineRunner(
                VerbCatalog.parse(VERBS), PipelineRunner.WRAPPER,
                SecretPrompt.REFUSE)).registerOn(r);
        return r;
    }

    private static JsonObject call(String type, String payload) {
        String res = router().dispatch("{\"v\":1,\"id\":\"t\","
                + "\"type\":\"" + type + "\",\"payload\":"
                + payload + "}");
        JsonObject o = JsonParser.parseString(res).getAsJsonObject();
        assertTrue(o.get("ok").getAsBoolean(), res);
        return o.getAsJsonObject("payload");
    }

    @Test
    void availableAdvertisesTheProtocolAndTheIds() {
        JsonObject p = call("pipeline.available", "{}");
        assertTrue(p.get("available").getAsBoolean());
        assertEquals("pkexec", p.get("mechanism").getAsString());
        assertEquals(VerbCatalog.PROTOCOL,
                p.get("verbsProtocol").getAsString());
        assertEquals(3, p.getAsJsonArray("verbs").size());
        assertEquals("doctor",
                p.getAsJsonArray("verbs").get(0).getAsString());
    }

    @Test
    void anUnknownVerbIsRefusedWithoutRunningAnything() {
        JsonObject p = call("pipeline.run",
                "{\"verb\":\"rm-rf\",\"params\":{}}");
        assertFalse(p.get("ok").getAsBoolean());
        assertTrue(p.get("error").getAsString()
                .contains("unknown verb"));
        assertEquals(-1, p.get("exitCode").getAsInt());
    }

    @Test
    void aPrivilegedVerbIsRefusedOnTheUnprivilegedMessage() {
        JsonObject p = call("pipeline.run",
                "{\"verb\":\"init-device\",\"params\":{}}");
        assertFalse(p.get("ok").getAsBoolean());
        assertTrue(p.get("error").getAsString()
                .contains("pipeline.privileged"));
    }

    @Test
    void anUnprivilegedVerbIsRefusedOnThePrivilegedMessage() {
        JsonObject p = call("pipeline.privileged",
                "{\"verb\":\"doctor\",\"params\":{}}");
        assertFalse(p.get("ok").getAsBoolean());
        assertTrue(p.get("error").getAsString()
                .contains("not marked privileged"));
    }

    @Test
    void aBadParameterIsRefusedBeforeAnyProcessOrPassword() {
        JsonObject p = call("pipeline.run",
                "{\"verb\":\"setup-step\","
                + "\"params\":{\"step\":\"a; reboot\"}}");
        assertFalse(p.get("ok").getAsBoolean());
        assertTrue(p.get("error").getAsString()
                .contains("does not match"));
    }

    @Test
    void aNonStringParameterCannotSlipPastTheRegex() {
        JsonObject p = call("pipeline.run",
                "{\"verb\":\"setup-step\","
                + "\"params\":{\"step\":[\"isle\"]}}");
        assertFalse(p.get("ok").getAsBoolean());
    }

    @Test
    void anAbsentVerbFieldIsJustAnUnknownVerb() {
        JsonObject p = call("pipeline.run", "{}");
        assertFalse(p.get("ok").getAsBoolean());
        assertTrue(p.has("error"));
    }

    @Test
    void anEmptyCatalogSaysSoRatherThanPretending() {
        BridgeRouter r = new BridgeRouter();
        new PipelineBridge(new PipelineRunner(VerbCatalog.empty(),
                PipelineRunner.WRAPPER, SecretPrompt.REFUSE))
                .registerOn(r);
        JsonObject p = JsonParser.parseString(r.dispatch(
                "{\"v\":1,\"id\":\"t\",\"type\":"
                + "\"pipeline.available\",\"payload\":{}}"))
                .getAsJsonObject().getAsJsonObject("payload");
        assertFalse(p.get("available").getAsBoolean());
        assertEquals(0, p.getAsJsonArray("verbs").size());
    }

    @Test
    void stdoutThatIsNotJsonIsNotParsed() {
        assertEquals(null, PipelineRunner.parse("REFUSED: 3 fail"));
        assertEquals(null, PipelineRunner.parse(""));
        assertEquals(null, PipelineRunner.parse("42"));
        assertTrue(PipelineRunner.parse("{\"a\":1}").isJsonObject());
    }
}
