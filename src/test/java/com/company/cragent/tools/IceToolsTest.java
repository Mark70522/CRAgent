package com.company.cragent.tools;

import com.company.cragent.cockpit.CockpitModel.Task;
import com.company.cragent.cockpit.CockpitStore;
import com.company.cragent.config.CockpitProperties;
import com.company.cragent.config.IceProperties;
import com.company.cragent.config.ServiceNowProperties;
import com.company.cragent.ice.IceClient;
import com.company.cragent.ice.IceEndpoints;
import com.company.cragent.ice.IceHttp;
import com.company.cragent.ice.IceRecord;
import com.company.cragent.ice.YamlIceClient;
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
        public IceRecord create(String changeNumber, Map<String, Object> fields) { calls.add("create " + changeNumber + " " + fields); return new IceRecord("ICE-77", Map.of("id", "ICE-77"), null); }
        public IceRecord update(String iceId, Map<String, Object> fields) { calls.add("update " + iceId + " " + fields); return new IceRecord(iceId, Map.of("id", iceId), null); }
        public String describe() { return "recording"; }
        public boolean configured() { return configured; }
    }

    @TempDir Path tmp;
    RecordingIce ice = new RecordingIce();
    CockpitStore cockpit;
    IceTools tools;

    @BeforeEach
    void setUp() {
        cockpit = new CockpitStore(new CockpitProperties(tmp, 0, false), new ObjectMapper());
        tools = new IceTools(ice, cockpit);
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
                Map.of("create-ice", new ServiceNowProperties.Endpoint("POST", "/ice", null, null, "{\"cr\": ${number}, \"data\": ${fields}}", "data", null, null),
                       "update-ice", new ServiceNowProperties.Endpoint("PUT", "/ice/${id}", null, null, null, "data", null, null)));
        ObjectMapper json = new ObjectMapper();
        YamlIceClient client = new YamlIceClient(new IceEndpoints(props, new IceHttp(props, json), json), props);

        assertThat(client.configured()).isTrue();
        IceRecord created = client.create("CHG0001", Map.of("title", "patch"));
        assertThat(created.id()).isEqualTo("ICE-9");
        IceRecord updated = client.update("ICE-9", Map.of("state", "closed"));
        assertThat(updated.id()).isEqualTo("ICE-9");
        assertThat(seen.get(0)).startsWith("POST /ice {\"cr\": \"CHG0001\", \"data\": {\"title\":\"patch\"}} auth=Basic ");
        assertThat(seen.get(1)).startsWith("PUT /ice/ICE-9 ");
        assertThat(seen.get(1)).contains("\"state\":\"closed\"");
    }
}
