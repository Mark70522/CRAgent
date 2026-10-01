package com.company.cragent.servicenow;

import com.company.cragent.config.ServiceNowProperties;
import com.company.cragent.config.ServiceNowProperties.Endpoint;
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
import org.springframework.web.util.UriComponentsBuilder;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The only piece of code that talks to ServiceNow. It knows nothing about tables or columns:
 * it executes the endpoints described in cr-agent.yml (servicenow.endpoints), substituting ${placeholders}
 * from the parameters it is given, adding the configured auth and headers, and picking the record out of
 * the response with the endpoint's {@code result} path.
 */
@Component
public class EndpointClient {

    private static final Logger log = LoggerFactory.getLogger(EndpointClient.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z0-9_.-]+)}");

    private final ServiceNowProperties props;
    private final ObjectMapper json;
    private RestClient http;   // built lazily so the app starts even when ServiceNow is not configured yet

    public EndpointClient(ServiceNowProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    public boolean configured() { return props.configured(); }

    public boolean has(String endpoint) { return props.endpoints().containsKey(endpoint); }

    /** Names and descriptions of the endpoints in cr-agent.yml. */
    public Map<String, String> endpoints() {
        Map<String, String> m = new LinkedHashMap<>();
        props.endpoints().forEach((k, e) -> m.put(k, e.method() + " " + e.path() + (e.description() == null ? "" : "  - " + e.description())));
        return m;
    }

    /** Executes an endpoint and returns the node at its {@code result} path (the whole response when result is empty). */
    public JsonNode call(String endpoint, Map<String, Object> params) {
        Endpoint ep = props.endpoints().get(endpoint);
        if (ep == null) throw new ServiceNowException("No endpoint named '" + endpoint + "' in cr-agent.yml. Configured: " + props.endpoints().keySet());
        if (props.baseUrl() == null || props.baseUrl().isBlank()) throw new ServiceNowException("servicenow.base-url is not set in cr-agent.yml");
        Map<String, Object> p = params == null ? Map.of() : params;

        UriComponentsBuilder b = UriComponentsBuilder.fromPath(renderText(ep.path(), p));
        ep.query().forEach((k, v) -> { String val = renderText(v, p); if (!val.isEmpty()) b.queryParam(k, val); });
        String uri = b.build().encode().toUriString();

        String body = null;
        HttpMethod method = HttpMethod.valueOf(ep.method());
        if (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) {
            body = ep.body() == null || ep.body().isBlank() ? toJson(p) : renderJson(ep.body(), p);
        }

        log.info("{} {} {}", ep.method(), uri, body == null ? "" : "(body " + body.length() + " chars)");
        try {
            RestClient.RequestBodySpec req = client().method(method).uri(uri);
            ep.headers().forEach((k, v) -> req.header(k, renderText(v, p)));
            if (body != null) req.contentType(MediaType.APPLICATION_JSON).body(body);
            String resp = req.retrieve().body(String.class);
            JsonNode root = resp == null || resp.isBlank() ? json.createObjectNode() : json.readTree(resp);
            JsonNode out = path(root, ep.result());
            if (out.isMissingNode()) throw new ServiceNowException("Response of '" + endpoint + "' has nothing at result path '" + ep.result() + "'. Response starts: " + head(resp));
            return out;
        } catch (RestClientResponseException e) {
            throw new ServiceNowException(ep.method() + " " + uri + " -> HTTP " + e.getStatusCode().value() + ": " + head(e.getResponseBodyAsString()));
        } catch (ServiceNowException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceNowException(ep.method() + " " + uri + " failed: " + e.getMessage(), e);
        }
    }

    /** Flattens a record to string values for display and rule checks; nested objects/arrays become JSON text. */
    public Map<String, String> flatten(JsonNode node) {
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

    public List<Map<String, String>> flattenList(JsonNode node) {
        List<Map<String, String>> out = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(n -> out.add(flatten(n)));
        else if (node != null && node.isObject()) out.add(flatten(node));
        return out;
    }

    public static JsonNode path(JsonNode root, String p) {
        if (p == null || p.isBlank()) return root;
        JsonNode n = root;
        for (String part : p.split("\\.")) {
            if (n == null) return MissingNode.getInstance();
            n = part.matches("\\d+") && n.isArray() ? n.get(Integer.parseInt(part)) : n.get(part);
        }
        return n == null ? MissingNode.getInstance() : n;
    }

    // ------------------------------------------------------------------ rendering

    /** ${name} -> text value (empty when missing). */
    String renderText(String template, Map<String, Object> p) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = lookup(p, m.group(1));
            String s = v == null ? "" : (v instanceof String || v instanceof Number || v instanceof Boolean) ? String.valueOf(v) : toJson(v);
            m.appendReplacement(sb, Matcher.quoteReplacement(s));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** ${name} -> JSON value: strings quoted, maps/lists as JSON, missing -> null. The result must be valid JSON. */
    String renderJson(String template, Map<String, Object> p) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(toJson(lookup(p, m.group(1)))));
        m.appendTail(sb);
        String out = sb.toString();
        try { json.readTree(out); } catch (Exception e) { throw new ServiceNowException("Rendered body is not valid JSON: " + head(out)); }
        return out;
    }

    private static Object lookup(Map<String, Object> p, String name) {
        Object cur = p;
        for (String part : name.split("\\.")) {
            if (!(cur instanceof Map<?, ?> map)) return null;
            cur = map.get(part);
        }
        return cur;
    }

    private String toJson(Object v) {
        try { return json.writeValueAsString(v); } catch (Exception e) { return "null"; }
    }

    private static String head(String s) { return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "..." : s; }

    private synchronized RestClient client() {
        if (http != null) return http;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(props.timeout());
        f.setReadTimeout(props.timeout());
        if (props.proxyHost() != null && !props.proxyHost().isBlank())
            f.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(props.proxyHost(), props.proxyPort() == null ? 8080 : props.proxyPort())));
        RestClient.Builder b = RestClient.builder().requestFactory(f).baseUrl(props.baseUrl())
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
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

    /** A secret from the yml (discouraged) or from the named environment variable. */
    private static String secret(String direct, String envName, String defaultEnv) {
        if (direct != null && !direct.isBlank()) return direct;
        String env = envName == null || envName.isBlank() ? defaultEnv : envName;
        String v = System.getenv(env);
        if (v == null || v.isBlank()) throw new ServiceNowException("Environment variable " + env + " is not set (needed for servicenow.auth)");
        return v;
    }
}
