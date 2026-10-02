package com.company.cragent.tools;

import com.company.cragent.cockpit.CockpitModel.Task;
import com.company.cragent.cockpit.CockpitStore;
import com.company.cragent.cockpit.RecordCache;
import com.company.cragent.config.CockpitProperties;
import com.company.cragent.config.IceProperties;
import com.company.cragent.config.ServiceNowProperties;
import com.company.cragent.ice.IceClient;
import com.company.cragent.ice.IceEndpoints;
import com.company.cragent.ice.IceHttp;
import com.company.cragent.ice.IceRecord;
import com.company.cragent.ice.YamlIceClient;
import com.company.cragent.validation.RuleEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The guards around the two ICE operations, and the yaml client rendering the ice: section against a local server. */
class IceToolsTest {

    static class RecordingIce implements IceClient {
        final List<String> calls = new ArrayList<>();
        boolean configured = true;
        public IceRecord get(String iceId) { calls.add("get " + iceId); return new IceRecord(iceId, Map.of("id", iceId, "title", "patch"), null); }
        public IceRecord create(String changeNumber, Map<String, Object> fields) { calls.add("create " + changeNumber + " " + fields); return new IceRecord("ICE-77", Map.of("id", "ICE-77"), null); }
        public IceRecord update(String iceId, Map<String, Object> fields) { calls.add("update " + iceId + " " + fields); return new IceRecord(iceId, Map.of("id", iceId), null); }
        public String describe() { return "recording"; }
        public boolean configured() { return configured; }
    }

    @TempDir Path tmp;
    RecordingIce ice = new RecordingIce();
    CockpitStore cockpit;
    RecordCache cache;
    IceTools tools;

    ServiceNowTools snTools;

    @BeforeEach
    void setUp() {
        CockpitProperties props = new CockpitProperties(tmp, 0, false);
        cockpit = new CockpitStore(props, new ObjectMapper());
        cache = new RecordCache(props, new ObjectMapper());
        snTools = new ServiceNowTools(new ServiceNowToolsTest.RecordingClient(), new RuleEngine(Path.of("knowledge/rules/hard-rules.yaml")), cockpit, cache);
        IceProperties iceProps = new IceProperties(null, null, null, null, null, null, null, null, null, null,
                Map.of("title", "${short_description}", "window", "${start_date} - ${end_date}", "cr", "${number}", "owner", "${assigned_to}"));
        tools = new IceTools(ice, cockpit, cache, iceProps, snTools, new ObjectMapper());
    }

    @Test
    void draftIceDerivesFieldsFromTheChange() {
        snTools.createChange(ServiceNowToolsTest.goodDraft(), true, null);   // now cached as CHG0099
        Map<String, Object> d = tools.draftIce("CHG0099");
        @SuppressWarnings("unchecked") Map<String, String> f = (Map<String, String>) d.get("fields");
        assertThat(f).containsEntry("title", "[PATCH] srv-app-01 - 2026-10 Windows monthly security patches")
                     .containsEntry("window", "2026-10-11 01:00:00 - 2026-10-11 05:00:00")
                     .containsEntry("cr", "CHG0099")
                     .containsEntry("owner", "");
        assertThat((List<String>) d.get("emptyFields")).containsExactly("owner");
        assertThat(d).doesNotContainKey("hint");

        // not cached and not readable -> the error from the interface, not a silent empty draft
        assertThatThrownBy(() -> tools.draftIce("CHG0000")).hasMessageContaining("not found");
    }

    @Test
    void readsAreCachedWithTheirChangeNumber() {
        Map<String, Object> out = tools.getIce("ICE-5", "CHG0001");
        assertThat(out.get("id")).isEqualTo("ICE-5");
        assertThat(out.get("changeNumber")).isEqualTo("CHG0001");
        assertThat(cache.get("ice", "ICE-5")).isPresent();
        assertThat(cache.list().get(0).get("changeNumber")).isEqualTo("CHG0001");
        assertThat(cache.list().get(0).get("title")).isEqualTo("patch");

        // a later read without the number keeps the one we knew
        tools.getIce("ICE-5", null);
        assertThat(cache.get("ice", "ICE-5").orElseThrow().get("changeNumber")).isEqualTo("CHG0001");
        assertThat(ice.calls).containsExactly("get ICE-5", "get ICE-5");
    }

    @Test
    void writesNeedConfirmationAndConfiguration() {
        assertThatThrownBy(() -> tools.createIce("CHG0001", Map.of("title", "x"), false, null)).hasMessageContaining("confirmation");
        assertThatThrownBy(() -> tools.updateIce("ICE-1", Map.of("title", "x"), false)).hasMessageContaining("confirmation");
        assertThat(ice.calls).isEmpty();

        ice.configured = false;
        assertThatThrownBy(() -> tools.createIce("CHG0001", Map.of(), true, null)).hasMessageContaining("ICE is not configured");
        assertThat(ice.calls).isEmpty();
    }

    @Test
    void createLinksTheTaskAndUpdateGoesThrough() {
        Task t = new Task(); t.title = "CCS PROD patch";
        String id = cockpit.addTasks(List.of(t), "paste").created().get(0).id;
        cockpit.linkChange(id, "CHG0001");

        Map<String, Object> out = tools.createIce("CHG0001", Map.of("title", "patch"), true, id);
        assertThat(out.get("id")).isEqualTo("ICE-77");
        assertThat(cockpit.task(id).orElseThrow().ice).isEqualTo("ICE-77");
        assertThat(cockpit.task(id).orElseThrow().cr).isEqualTo("CHG0001");
        assertThat(cockpit.taskNotes(id)).contains("ICE-77");

        tools.updateIce("ICE-77", Map.of("window", "2026-10-11 01:00"), true);
        assertThat(ice.calls).containsExactly("create CHG0001 {title=patch}", "update ICE-77 {window=2026-10-11 01:00}");
    }

    // ---- yaml client over a local server: the ice: section is rendered exactly like the servicenow one

    HttpServer server;
    final List<String> seen = new ArrayList<>();

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    @Test
    void yamlClientRendersTheIceSection() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " " + body + " auth=" + ex.getRequestHeaders().getFirst("Authorization"));
            byte[] resp = "{\"data\":{\"iceId\":\"ICE-9\",\"state\":\"new\"}}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        server.start();

        IceProperties props = new IceProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                new ServiceNowProperties.Auth("basic", null, null, "svc", null, "pw"),
                null, null, null, null, null, "iceId",
                Map.of("get-ice", new ServiceNowProperties.Endpoint("GET", "/ice/${id}", null, null, null, "data", null, null),
                       "create-ice", new ServiceNowProperties.Endpoint("POST", "/ice", null, null, "{\"cr\": ${number}, \"data\": ${fields}}", "data", null, null),
                       "update-ice", new ServiceNowProperties.Endpoint("PUT", "/ice/${id}", null, null, null, "data", null, null)),
                null, null);
        ObjectMapper json = new ObjectMapper();
        YamlIceClient client = new YamlIceClient(new IceEndpoints(props, new IceHttp(props, json), json), props);

        assertThat(client.configured()).isTrue();
        assertThat(client.get("ICE-9").id()).isEqualTo("ICE-9");
        assertThat(seen.get(0)).startsWith("GET /ice/ICE-9 ");
        seen.clear();
        IceRecord created = client.create("CHG0001", Map.of("title", "patch"));
        assertThat(created.id()).isEqualTo("ICE-9");
        IceRecord updated = client.update("ICE-9", Map.of("state", "closed"));
        assertThat(updated.id()).isEqualTo("ICE-9");
        assertThat(seen.get(0)).startsWith("POST /ice {\"cr\": \"CHG0001\", \"data\": {\"title\":\"patch\"}} auth=Basic ");
        assertThat(seen.get(1)).startsWith("PUT /ice/ICE-9 ");
        assertThat(seen.get(1)).contains("\"state\":\"closed\"");
    }
}
