package com.company.cragent.config;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads cr-agent.yml the way the program will use it and says what is missing or looks wrong, before any
 * call is made: endpoint names, placeholders, result paths, auth environment variables, field-map, from-change.
 * Problems stop things from working; notes are worth knowing.
 */
@Component
public class ConfigCheck {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");
    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_.-]+");

    private final ServiceNowProperties sn;
    private final IceProperties ice;

    public ConfigCheck(ServiceNowProperties sn, IceProperties ice) {
        this.sn = sn;
        this.ice = ice;
    }

    public record Result(boolean ok, List<String> problems, List<String> notes) {}

    public Result run() {
        List<String> problems = new ArrayList<>(), notes = new ArrayList<>();
        checkApi("servicenow", sn, List.of("get-change", "create-change", "update-change"),
                Map.of("get-change", "number", "update-change", "number"), problems, notes);
        if (ice.configured() || !ice.endpoints().isEmpty() || (ice.baseUrl() != null && !ice.baseUrl().isBlank())) {
            checkApi("ice", ice, List.of("get-ice", "create-ice", "update-ice"),
                    Map.of("get-ice", "id", "update-ice", "id", "create-ice", "number"), problems, notes);
            if (ice.fromChange().isEmpty()) notes.add("ice.from-change not set: draft_ice cannot derive ICE fields from a CR; Copilot composes them by hand");
            ice.fromChange().forEach((k, v) -> checkPlaceholders("ice.from-change." + k, v, problems));
        } else {
            notes.add("ice: not configured (ICE tools are off)");
        }
        return new Result(problems.isEmpty(), problems, notes);
    }

    private void checkApi(String name, HttpApi api, List<String> required, Map<String, String> mustMention, List<String> problems, List<String> notes) {
        boolean anything = (api.baseUrl() != null && !api.baseUrl().isBlank()) || !api.endpoints().isEmpty();
        if (!anything) { notes.add(name + ": not configured"); return; }
        if (api.baseUrl() == null || api.baseUrl().isBlank()) problems.add(name + ".base-url is empty");
        else if (!api.baseUrl().matches("https?://.+")) problems.add(name + ".base-url should start with http:// or https://: " + api.baseUrl());

        if ("company".equals(api.client())) {
            notes.add(name + ".client=company: endpoints below are ignored, your Company*Client is used");
        } else {
            for (String ep : required) if (!api.endpoints().containsKey(ep)) problems.add(name + ".endpoints." + ep + " is missing (names are fixed: " + String.join(", ", required) + ")");
            api.endpoints().forEach((epName, ep) -> {
                if (!required.contains(epName)) notes.add(name + ".endpoints." + epName + ": unknown name, nothing calls it");
                if (ep.path() == null || ep.path().isBlank()) problems.add(name + ".endpoints." + epName + ".path is empty");
                if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(ep.method())) problems.add(name + ".endpoints." + epName + ".method '" + ep.method() + "' is not GET/POST/PUT/PATCH/DELETE");
                checkPlaceholders(name + ".endpoints." + epName + ".path", ep.path(), problems);
                ep.query().forEach((k, v) -> checkPlaceholders(name + ".endpoints." + epName + ".query." + k, v, problems));
                ep.headers().forEach((k, v) -> checkPlaceholders(name + ".endpoints." + epName + ".headers." + k, v, problems));
                checkPlaceholders(name + ".endpoints." + epName + ".body", ep.body(), problems);
                if (ep.body() != null && !ep.body().isBlank() && !"GET".equals(ep.method()) && !ep.body().trim().startsWith("{") && !ep.body().trim().startsWith("["))
                    problems.add(name + ".endpoints." + epName + ".body must be a JSON template starting with { or [");
                if ("GET".equals(ep.method()) && ep.body() != null && !ep.body().isBlank()) notes.add(name + ".endpoints." + epName + ": body is ignored on GET");
                String must = mustMention.get(epName);
                if (must != null) {
                    String all = ep.path() + " " + ep.query() + " " + ep.headers() + " " + (ep.body() == null ? "" : ep.body());
                    if (!all.contains("${" + must + "}")) problems.add(name + ".endpoints." + epName + " never uses ${" + must + "}: the call has no way to say which record");
                }
                if (ep.result() == null || ep.result().isBlank()) notes.add(name + ".endpoints." + epName + ".result empty: the whole response is treated as the record");
            });
        }

        ServiceNowProperties.Auth a = api.auth();
        switch (a.type()) {
            case "bearer" -> checkSecret(name, a.token(), a.tokenEnv(), "SN_TOKEN", problems, notes);
            case "basic" -> {
                if (a.user() == null || a.user().isBlank()) problems.add(name + ".auth.user is empty (basic auth)");
                checkSecret(name, a.password(), a.passwordEnv(), "SN_PASSWORD", problems, notes);
            }
            case "none" -> notes.add(name + ".auth: none");
            default -> problems.add(name + ".auth.type '" + a.type() + "' is not bearer / basic / none");
        }
        api.fieldMap().forEach((k, v) -> { if (k == null || k.isBlank() || v == null || v.isBlank()) problems.add(name + ".field-map has an empty name: " + k + " -> " + v); });
        if (!api.fieldMap().isEmpty()) notes.add(name + ".field-map: " + api.fieldMap().size() + " field(s) renamed at the boundary");
    }

    private static void checkSecret(String name, String direct, String env, String defaultEnv, List<String> problems, List<String> notes) {
        if (direct != null && !direct.isBlank()) { notes.add(name + ".auth: secret is written in cr-agent.yml; prefer an environment variable"); return; }
        String e = env == null || env.isBlank() ? defaultEnv : env;
        String v = System.getenv(e);
        if (v == null || v.isBlank()) problems.add(name + ".auth: environment variable " + e + " is not set (setx " + e + " ... then restart IntelliJ)");
    }

    private static void checkPlaceholders(String where, String text, List<String> problems) {
        if (text == null) return;
        int open = text.indexOf("${");
        while (open >= 0) {
            int close = text.indexOf('}', open);
            if (close < 0) { problems.add(where + ": '${' without closing '}'"); return; }
            open = text.indexOf("${", close);
        }
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) if (!NAME.matcher(m.group(1)).matches()) problems.add(where + ": bad placeholder '${" + m.group(1) + "}' (letters, digits, _ . - only)");
    }

    /** For status: a short map. */
    public Map<String, Object> summary() {
        Result r = run();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok());
        m.put("problems", r.problems());
        m.put("notes", r.notes());
        return m;
    }
}
