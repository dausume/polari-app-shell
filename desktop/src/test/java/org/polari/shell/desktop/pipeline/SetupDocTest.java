package org.polari.shell.desktop.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * ci-11b: the first-run panel is a RENDERER, so the only thing it
 * can get wrong on its own is reading the document. It must never
 * fail on a field the bash half has not shipped yet.
 */
class SetupDocTest {

    private static final String DOC = """
        {"protocol":"polari-pipeline-setup/1",
         "steps":[
          {"id":"role","title":"this device's role",
           "explain":"controller 2 GB, build ~3 GB, VM 4 GB",
           "where":"device.env",
           "checks":[{"label":"RAM","state":"ok","detail":"15.5 GB"},
                     {"label":"/dev/kvm","state":"fail",
                      "detail":"absent","fix":"pol jenkins target ssh"}],
           "questions":[{"id":"target","kind":"choice",
                         "label":"where the isle goes",
                         "options":["local","ssh"],"value":"local",
                         "verb":"setup-run",
                         "params":{"action":"{answer}"}}],
           "actions":[{"id":"install-tools","label":"install them",
                       "verb":"apt-install-tools"}]},
          {"id":"summary","title":"summary"}],
         "todo":[{"what":"put this device on a wire",
                  "how":"nmcli con up"},
                 "a plain string is fine too"],
         "summary":{"complete":3,"total":8,"ready":false,
                    "blocking":"no /dev/kvm"}}""";

    private static SetupDoc read(String json) {
        return SetupDoc.of(JsonParser.parseString(json));
    }

    @Test
    void readsTheWholeDocument() {
        SetupDoc d = read(DOC);
        assertTrue(d.looksValid());
        assertEquals(2, d.steps.size());
        SetupDoc.Step s = d.steps.get(0);
        assertEquals("role", s.id);
        assertEquals(2, s.checks.size());
        assertEquals("pol jenkins target ssh", s.checks.get(1).fix);
        assertEquals(1, s.questions.size());
        assertEquals("choice", s.questions.get(0).kind);
        assertEquals(2, s.questions.get(0).options.size());
        assertEquals(PipelineSetupPanel.ANSWER,
                s.questions.get(0).params.get("action"));
        assertTrue(s.questions.get(0).runnable());
        assertEquals("apt-install-tools",
                s.actions.get(0).verb);
        assertEquals(2, d.todo.size());
        assertTrue(d.todo.get(0).contains("nmcli con up"));
        assertEquals(3, d.complete);
        assertEquals(8, d.total);
        assertFalse(d.ready);
        assertEquals("no /dev/kvm", d.blocking);
    }

    @Test
    void anUnknownProtocolIsNotValid() {
        assertFalse(read("{\"protocol\":\"other/1\",\"steps\":[{}]}")
                .looksValid());
        assertFalse(read("{\"protocol\":"
                + "\"polari-pipeline-setup/1\"}").looksValid());
        assertFalse(SetupDoc.of(null).looksValid());
        assertFalse(SetupDoc.of(JsonParser.parseString("[]"))
                .looksValid());
    }

    @Test
    void everyFieldIsOptional() {
        SetupDoc d = read("{\"protocol\":"
                + "\"polari-pipeline-setup/1\",\"steps\":["
                + "{\"id\":\"bare\"}]}");
        assertTrue(d.looksValid());
        SetupDoc.Step s = d.steps.get(0);
        assertEquals("", s.explain);
        assertTrue(s.checks.isEmpty());
        assertTrue(s.questions.isEmpty());
        assertTrue(s.actions.isEmpty());
        assertEquals(0, d.total);
    }

    @Test
    void aQuestionWithNoVerbIsNotRunnable() {
        SetupDoc d = read("{\"protocol\":"
                + "\"polari-pipeline-setup/1\",\"steps\":[{"
                + "\"id\":\"x\",\"questions\":[{\"id\":\"q\","
                + "\"kind\":\"text\"}]}]}");
        assertFalse(d.steps.get(0).questions.get(0).runnable());
    }

    @Test
    void alternativeKeyNamesAreAccepted() {
        // the bash half may say name/verdict/value instead of
        // id/state/detail — a renderer should not be brittle
        SetupDoc d = read("{\"protocol\":"
                + "\"polari-pipeline-setup/1\",\"steps\":[{"
                + "\"name\":\"isle\",\"checks\":[{\"name\":\"kvm\","
                + "\"verdict\":\"PASS\",\"value\":\"present\"}]}]}");
        assertEquals("isle", d.steps.get(0).id);
        assertEquals("kvm", d.steps.get(0).checks.get(0).label);
        assertEquals("present", d.steps.get(0).checks.get(0).detail);
    }
}
