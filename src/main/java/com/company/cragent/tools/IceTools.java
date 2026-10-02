package com.company.cragent.tools;

import com.company.cragent.cockpit.CockpitStore;
import com.company.cragent.cockpit.RecordCache;
import com.company.cragent.ice.IceClient;
import com.company.cragent.ice.IceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** The three ICE operations as Copilot tools: read, create, update. Writes need the user's confirmation. Every result is cached locally. */
@Component
public class IceTools {

    private static final Logger log = LoggerFactory.getLogger(IceTools.class);

    private final IceClient ice;
    private final CockpitStore cockpit;
    private final RecordCache cache;

    public IceTools(IceClient ice, CockpitStore cockpit, RecordCache cache) {
        this.ice = ice;
        this.cockpit = cockpit;
        this.cache = cache;
    }

    @Tool(name = "get_ice", description = """
            Read one ICE record by id. Returns its fields and raw JSON, and stores a local copy under
            cockpit/records/ice/ that the cockpit page shows. Pass changeNumber when you know which CR it belongs to.""")
    public Map<String, Object> getIce(
            @ToolParam(description = "ICE record id") String iceId,
            @ToolParam(description = "The CR number this record belongs to, if known", required = false) String changeNumber) {
        ready();
        return cache.putIce(describe(ice.get(iceId.trim())), "get", changeNumber);
    }

    @Tool(name = "create_ice", description = """
            Register a change request in ICE (the second system every CR has to be entered in). fields are named
            exactly as the ICE interface expects. Set confirmed=true only after the user saw the fields and said
            to send them. Pass taskId to record the ICE id on the cockpit task that owns this CR.
            Refuses with a clear message when ICE is not configured (status shows it).""")
    public Map<String, Object> createIce(
            @ToolParam(description = "Change request number this ICE record is for") String changeNumber,
            @ToolParam(description = "ICE field name -> value") Map<String, Object> fields,
            @ToolParam(description = "Must be true; pass true only after the user explicitly confirmed") boolean confirmed,
            @ToolParam(description = "Cockpit task id (T-0001), if any", required = false) String taskId) {
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

    @Tool(name = "update_ice", description = """
            Update fields of an existing ICE record; field names exactly as the ICE interface expects.
            Set confirmed=true only after the user explicitly agreed to the change.""")
    public Map<String, Object> updateIce(
            @ToolParam(description = "ICE record id") String iceId,
            @ToolParam(description = "ICE field name -> new value") Map<String, Object> fields,
            @ToolParam(description = "Must be true; pass true only after the user explicitly confirmed") boolean confirmed) {
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
