package com.company.cragent.tools;

import com.company.cragent.cockpit.CockpitStore;
import com.company.cragent.cockpit.RecordCache;
import com.company.cragent.config.IceProperties;
import com.company.cragent.ice.IceClient;
import com.company.cragent.ice.IceRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The three ICE operations as Copilot tools: read, create, update. Writes need the user's confirmation. Every result is cached locally. */
@Component
public class IceTools {

    private static final Logger log = LoggerFactory.getLogger(IceTools.class);

    private final IceClient ice;
    private final CockpitStore cockpit;
    private final RecordCache cache;
    private final IceProperties props;
    private final ServiceNowTools sn;
    private final ObjectMapper json;

    public IceTools(IceClient ice, CockpitStore cockpit, RecordCache cache, IceProperties props, ServiceNowTools sn, ObjectMapper json) {
        this.ice = ice;
        this.cockpit = cockpit;
        this.cache = cache;
        this.props = props;
        this.sn = sn;
        this.json = json;
    }

    @Tool(name = "draft_ice", description = "ICE fields derived from a CR by ice.from-change in cr-agent.yml; a hint means it is not configured.")
    public Map<String, Object> draftIce(String changeNumber) {
        String number = changeNumber.trim();
        Map<String, Object> cr = cache.get("change", number).orElseGet(() -> sn.getChange(number));
        @SuppressWarnings("unchecked") Map<String, Object> crFields = cr.get("fields") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> params = new LinkedHashMap<>(crFields);
        params.put("number", number);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changeNumber", number);
        Map<String, String> fields = new LinkedHashMap<>();
        List<String> empty = new ArrayList<>();
        props.fromChange().forEach((iceField, template) -> {
            String v = render(template, params);
            fields.put(iceField, v);
            if (v.isBlank()) empty.add(iceField);
        });
        out.put("fields", fields);
        if (props.fromChange().isEmpty()) out.put("hint", "ice.from-change is not configured in cr-agent.yml; compose the ICE fields from the CR by hand and tell the user which CR fields you used.");
        if (!empty.isEmpty()) out.put("emptyFields", empty);
        out.put("crTitle", crFields.getOrDefault("short_description", ""));
        return out;
    }

    private static final java.util.regex.Pattern PH = java.util.regex.Pattern.compile("\\$\\{([a-zA-Z0-9_.-]+)}");

    private String render(String template, Map<String, Object> p) {
        java.util.regex.Matcher m = PH.matcher(template == null ? "" : template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = p.get(m.group(1));
            String s = v == null ? "" : v instanceof String || v instanceof Number || v instanceof Boolean ? String.valueOf(v) : toJson(v);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(s));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String toJson(Object v) { try { return json.writeValueAsString(v); } catch (Exception e) { return String.valueOf(v); } }

    @Tool(name = "get_ice", description = "Read an ICE record.")
    public Map<String, Object> getIce(
            String iceId,
            @ToolParam(description = "Its CR, if known", required = false) String changeNumber) {
        ready();
        return cache.putIce(describe(ice.get(iceId.trim())), "get", changeNumber);
    }

    @Tool(name = "ice_score", description = "Current ICE score of a record (kept in its score history).")
    public Map<String, Object> iceScore(String iceId) {
        ready();
        IceRecord rec = ice.score(iceId.trim());
        String score = rec.text(props.scoreField());
        Map<String, Object> out = new LinkedHashMap<>(cache.putScore(iceId.trim(), score, describe(rec)));
        out.put("score", score);
        if (score.isBlank()) out.put("hint", "no key '" + props.scoreField() + "' in the response: set ice.score-field in cr-agent.yml to the right key (response keys: " + rec.fields().keySet() + ")");
        return out;
    }

    @Tool(name = "create_ice", description = "Register a CR in ICE. taskId records the ICE id on the cockpit task.")
    public Map<String, Object> createIce(
            String changeNumber,
            Map<String, Object> fields,
            @ToolParam(description = "true only after the user explicitly agreed") boolean confirmed,
            @ToolParam(description = "T-0001", required = false) String taskId) {
        ready();
        if (!confirmed) throw new IllegalStateException("Not sent: show the user the ICE fields and ask for confirmation, then call again with confirmed=true.");
        IceRecord rec = ice.create(changeNumber.trim(), fields == null ? Map.of() : fields);
        log.info("created ICE {} for {}", rec.id(), changeNumber);
        Map<String, Object> out = cache.putIce(describe(rec), "create", changeNumber.trim());
        if (taskId != null && !taskId.isBlank() && rec.id() != null && !rec.id().isBlank()) {
            try {
                out.put("task", cockpit.updateTask(taskId.trim(), Map.of("ice", rec.id()), "ICE " + rec.id() + " 已创建(" + changeNumber + ")"));
            } catch (IllegalArgumentException e) {
                out.put("taskLinkError", e.getMessage());
            }
        }
        return out;
    }

    @Tool(name = "update_ice", description = "Change fields of an ICE record (only the changed ones).")
    public Map<String, Object> updateIce(
            String iceId,
            Map<String, Object> fields,
            @ToolParam(description = "true only after the user explicitly agreed") boolean confirmed) {
        ready();
        if (!confirmed) throw new IllegalStateException("Not updated: show the user exactly what will change in ICE and ask for confirmation, then call again with confirmed=true.");
        IceRecord rec = ice.update(iceId.trim(), fields == null ? Map.of() : fields);
        log.info("updated ICE {}", iceId);
        return cache.putIce(describe(rec), "update", null);
    }

    private void ready() {
        if (!ice.configured()) throw new IllegalStateException("ICE is not configured: add an ice: section (base-url, auth, endpoints create-ice / update-ice) to cr-agent.yml, or set ice.client: company and fill CompanyIceClient.");
    }

    private static Map<String, Object> describe(IceRecord rec) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", rec.id());
        out.put("fields", rec.fields());
        out.put("raw", rec.raw());
        return out;
    }
}
