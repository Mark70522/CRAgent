package com.company.cragent.servicenow;

import com.company.cragent.config.HttpApi;
import com.company.cragent.config.ServiceNowProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP plumbing shared by every ServiceNowClient implementation: base URL, Basic / Bearer auth from
 * environment variables, extra headers, proxy, timeouts, JSON in and out, readable errors.
 * Your implementation only decides which path to call and how to read the response.
 */
@Component
@org.springframework.context.annotation.Primary   // the ServiceNow one; IceHttp / IceEndpoints are asked for by their own type
public class SnHttp {

    private static final Logger log = LoggerFactory.getLogger(SnHttp.class);
    private final HttpApi props;
    private final ObjectMapper json;
    private RestClient http;   // built on first use so the app starts even before ServiceNow is configured

    @org.springframework.beans.factory.annotation.Autowired
    public SnHttp(ServiceNowProperties props, ObjectMapper json) {
        this((HttpApi) props, json);
    }

    /** Same plumbing over another configured interface (see IceHttp). */
    protected SnHttp(HttpApi props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    public ObjectMapper json() { return json; }
    public HttpApi props() { return props; }

    /** GET {base-url}{path}; path may include a query string. */
    public JsonNode get(String path) { return exchange(HttpMethod.GET, path, null); }

    /** POST {base-url}{path} with the object serialised as JSON. */
    public JsonNode post(String path, Object body) { return exchange(HttpMethod.POST, path, body); }

    public JsonNode put(String path, Object body) { return exchange(HttpMethod.PUT, path, body); }

    public JsonNode patch(String path, Object body) { return exchange(HttpMethod.PATCH, path, body); }

    /** Any method; body may be null, a String (sent as is) or an object (serialised as JSON). Errors carry status and response start. */
    public JsonNode exchange(HttpMethod method, String path, Object body) { return exchange(method, path, body, Map.of()); }

    /** Same, with extra headers for this one request. */
    public JsonNode exchange(HttpMethod method, String path, Object body, Map<String, String> headers) {
        if (props.baseUrl() == null || props.baseUrl().isBlank()) throw new ServiceNowException("base-url is not set in cr-agent.yml (servicenow: or ice: section)");
        log.info("{} {}", method, path);
        try {
            RestClient.RequestBodySpec req = client().method(method).uri(path);
            if (headers != null) headers.forEach(req::header);
            if (body != null) {
                String text = body instanceof String s ? s : json.writeValueAsString(body);
                req.contentType(MediaType.APPLICATION_JSON).body(text);
            }
            String resp = req.retrieve().body(String.class);
            return resp == null || resp.isBlank() ? json.createObjectNode() : json.readTree(resp);
        } catch (RestClientResponseException e) {
            throw new ServiceNowException(method + " " + path + " -> HTTP " + e.getStatusCode().value() + ": " + head(e.getResponseBodyAsString()));
        } catch (ServiceNowException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceNowException(method + " " + path + " failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ JSON helpers

    /** Node at a dot path ("data.items.0"); MissingNode when absent. */
    public static JsonNode path(JsonNode root, String p) {
        if (p == null || p.isBlank()) return root;
        JsonNode n = root;
        for (String part : p.split("\\.")) {
            if (n == null) return MissingNode.getInstance();
            n = part.matches("\\d+") && n.isArray() ? n.get(Integer.parseInt(part)) : n.get(part);
        }
        return n == null ? MissingNode.getInstance() : n;
    }

    /** Object node -> field name -> text; {value, display_value} objects become the display value; nested nodes become JSON text. */
    public static Map<String, String> flatten(JsonNode node) {
        Map<String, String> m = new LinkedHashMap<>();
        if (node == null || !node.isObject()) return m;
        node.fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            if (v.isObject() && v.has("display_value")) m.put(e.getKey(), v.get("display_value").asText());
            else if (v.isValueNode()) m.put(e.getKey(), v.isNull() ? "" : v.asText());
            else m.put(e.getKey(), v.toString());
        });
        return m;
    }

    public static List<Map<String, String>> flattenList(JsonNode node) {
        List<Map<String, String>> out = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(n -> out.add(flatten(n)));
        else if (node != null && node.isObject()) out.add(flatten(node));
        return out;
    }

    public static String head(String s) { return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "..." : s; }

    // ------------------------------------------------------------------ client

    private synchronized RestClient client() {
        if (http != null) return http;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(props.timeout());
        f.setReadTimeout(props.timeout());
        if (props.proxyHost() != null && !props.proxyHost().isBlank())
            f.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(props.proxyHost(), props.proxyPort() == null ? 8080 : props.proxyPort())));
        RestClient.Builder b = RestClient.builder().requestFactory(f).baseUrl(props.baseUrl()).defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        ServiceNowProperties.Auth a = props.auth();
        switch (a.type()) {
            case "bearer" -> b.defaultHeader("Authorization", "Bearer " + secret(a.token(), a.tokenEnv(), "SN_TOKEN"));
            case "basic" -> b.defaultHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (a.user() + ":" + secret(a.password(), a.passwordEnv(), "SN_PASSWORD")).getBytes(StandardCharsets.UTF_8)));
            case "none" -> { }
            default -> throw new ServiceNowException("servicenow.auth.type must be bearer, basic or none, got '" + a.type() + "'");
        }
        props.headers().forEach(b::defaultHeader);
        http = b.build();
        return http;
    }

    private static String secret(String direct, String envName, String defaultEnv) {
        if (direct != null && !direct.isBlank()) return direct;
        String env = envName == null || envName.isBlank() ? defaultEnv : envName;
        String v = System.getenv(env);
        if (v == null || v.isBlank()) throw new ServiceNowException("Environment variable " + env + " is not set (needed for servicenow.auth)");
        return v;
    }
}
