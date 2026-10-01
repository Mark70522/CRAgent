package com.company.cragent.servicenow;

import com.company.cragent.config.ServiceNowProperties;
import com.company.cragent.config.ServiceNowProperties.Auth;
import com.company.cragent.config.ServiceNowProperties.Endpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the endpoint description language against a tiny local HTTP server that records what it receives.
 * This is a test of the client, not sample data: the server only echoes back what the client sent.
 */
class EndpointClientTest {

    static HttpServer server;
    static final List<String> received = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " auth=" + ex.getRequestHeaders().getFirst("Authorization")
                    + " key=" + ex.getRequestHeaders().getFirst("X-Api-Key") + " body=" + body);
            byte[] resp;
            if (ex.getRequestURI().getPath().endsWith("/missing")) resp = "{\"data\":null}".getBytes(StandardCharsets.UTF_8);
            else if (ex.getRequestURI().getPath().contains("/error")) { ex.sendResponseHeaders(500, -1); return; }
            else resp = ("{\"data\":{\"number\":\"CHG0001\",\"short_description\":\"hello\",\"tasks\":[{\"order\":\"10\"}]},\"meta\":{\"items\":[{\"n\":1},{\"n\":2}]}}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, resp.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(resp); }
        });
        server.start();
    }

    @AfterAll
    static void stop() { server.stop(0); }

    static EndpointClient client(Auth auth, Map<String, Endpoint> endpoints) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new EndpointClient(new ServiceNowProperties(base, auth, Map.of("X-Api-Key", "k1"), null, null, 5000, "yaml", endpoints), new ObjectMapper());
    }

    @Test
    void rendersPathQueryBodyAndAuth() {
        var eps = Map.of(
                "get-change", new Endpoint("GET", "/change/${number}", Map.of("expand", "${expand}", "empty", "${nothing}"), null, null, "data", "tasks", null),
                "create-change", new Endpoint("POST", "/change", null, Map.of("X-Trace", "${number}"), "{\"request\": ${fields}, \"tasks\": ${tasks}, \"by\": ${user}}", "data", null, null),
                "list", new Endpoint("GET", "/change", null, null, null, "meta.items", null, null));
        EndpointClient c = client(new Auth("bearer", null, "tok-123", null, null, null), eps);

        received.clear();
        JsonNode rec = c.call("get-change", Map.of("number", "CHG0001", "expand", "tasks"));
        assertThat(rec.path("number").asText()).isEqualTo("CHG0001");
        assertThat(received.get(0)).startsWith("GET /change/CHG0001?expand=tasks auth=Bearer tok-123 key=k1");
        assertThat(received.get(0)).doesNotContain("empty=");   // placeholders without a value drop the query param

        received.clear();
        java.util.LinkedHashMap<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("short_description", "x");
        fields.put("risk", "Low");
        c.call("create-change", Map.of("number", "CHG0001", "user", "doug", "fields", fields, "tasks", List.of(Map.of("order", "10"))));
        assertThat(received.get(0)).contains("POST /change").contains("body={\"request\": {\"short_description\":\"x\",\"risk\":\"Low\"}, \"tasks\": [{\"order\":\"10\"}], \"by\": \"doug\"}");

        JsonNode items = c.call("list", Map.of());
        assertThat(items.isArray()).isTrue();
        assertThat(SnHttp.flattenList(items)).hasSize(2);
        assertThat(SnHttp.flatten(rec)).containsEntry("short_description", "hello").containsKey("tasks");
    }

    @Test
    void basicAuthAndDefaultBody() {
        var eps = Map.of("create-change", new Endpoint("POST", "/change", null, null, null, "data", null, null));
        EndpointClient c = client(new Auth("basic", null, null, "svc", null, "pw"), eps);
        received.clear();
        c.call("create-change", Map.of("a", "1"));
        assertThat(received.get(0)).contains("auth=Basic c3ZjOnB3").contains("body={\"a\":\"1\"}");
    }

    @Test
    void clearErrors() {
        var eps = Map.of(
                "get-change", new Endpoint("GET", "/change/${number}", null, null, null, "data", null, null),
                "bad-path", new Endpoint("GET", "/missing", null, null, null, "data.nothing", null, null),
                "boom", new Endpoint("GET", "/error", null, null, null, "", null, null));
        EndpointClient c = client(new Auth("none", null, null, null, null, null), eps);
        assertThatThrownBy(() -> c.call("nope", Map.of())).hasMessageContaining("No endpoint named 'nope'").hasMessageContaining("get-change");
        assertThatThrownBy(() -> c.call("bad-path", Map.of())).hasMessageContaining("result path 'data.nothing'");
        assertThatThrownBy(() -> c.call("boom", Map.of())).hasMessageContaining("HTTP 500");
        assertThatThrownBy(() -> client(new Auth("bearer", "CR_AGENT_TEST_NO_SUCH_VAR", null, null, null, null), eps).call("get-change", Map.of("number", "x")))
                .hasMessageContaining("CR_AGENT_TEST_NO_SUCH_VAR");
    }
}
