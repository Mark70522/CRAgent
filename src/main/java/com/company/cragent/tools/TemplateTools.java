package com.company.cragent.tools;

import com.company.cragent.cockpit.RecordCache;
import com.company.cragent.inventory.Inventory;
import com.company.cragent.knowledge.FormCatalog;
import com.company.cragent.model.ChangeDraft;
import com.company.cragent.model.CiInfo;
import com.company.cragent.template.ChangeTemplate;
import com.company.cragent.template.TemplateService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class TemplateTools {

    private final TemplateService templates;
    private final Inventory inv;
    private final ServiceNowTools sn;
    private final RecordCache cache;
    private final FormCatalog forms;

    public TemplateTools(TemplateService templates, Inventory inv, ServiceNowTools sn, RecordCache cache, FormCatalog forms) {
        this.templates = templates;
        this.inv = inv;
        this.sn = sn;
        this.cache = cache;
        this.forms = forms;
    }

    /** Never copied from an old CR: its identity, workflow state and system stamps. */
    static final Set<String> NOT_COPIED = Set.of("number", "state", "approval", "close_code", "close_notes", "opened_at", "closed_at",
            "work_notes", "comments", "id", "kind", "task", "iceId");

    @Tool(name = "draft_from_change", description = "Draft a new CR from an old one: fields and tasks copied, identity/state/read-only fields dropped, date-times cleared.")
    public DraftResult draftFromChange(
            String number,
            @ToolParam(description = "true = read the interface, not the local copy", required = false) Boolean live) {
        String n = number.trim();
        Map<String, Object> rec = Boolean.TRUE.equals(live) ? null : cache.get("change", n).orElse(null);
        if (rec == null) rec = sn.getChange(n);
        List<String> notes = new ArrayList<>();
        Set<String> dates = new LinkedHashSet<>();
        Map<String, Object> fields = copy(asMap(rec.get("fields")), forms.fields("change"), dates);
        List<Map<String, Object>> tasks = new ArrayList<>();
        if (rec.get("tasks") instanceof List<?> l) for (Object o : l) {
            Map<String, Object> t = asMap(o instanceof Map<?, ?> m && m.get("fields") instanceof Map<?, ?> inner ? inner : o);
            if (!t.isEmpty()) tasks.add(copy(t, forms.fields("task"), dates));
        }
        if (fields.isEmpty()) notes.add(n + " has no copyable fields (is the local copy empty? try live=true)");
        if (!dates.isEmpty()) notes.add("Cleared date-time fields, set them for this change: " + dates);
        notes.add("Copied from " + n + ": change the title and the description to this change before validating");
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fields", "copied from " + n + " (" + (rec.containsKey("fetchedAt") ? "local copy of " + rec.get("fetchedAt") : "read live") + ")");
        sources.put("tasks", tasks.size() + " task(s) copied from " + n);
        return new DraftResult(new ChangeDraft(fields, tasks), sources, notes);
    }

    private static Map<String, Object> copy(Map<String, Object> src, List<Map<String, Object>> catalog, Set<String> clearedDates) {
        Set<String> readonly = new HashSet<>(), datetime = new HashSet<>();
        for (Map<String, Object> f : catalog) {
            String k = String.valueOf(f.get("key"));
            if (Boolean.TRUE.equals(f.get("readonly"))) readonly.add(k);
            if ("datetime".equals(f.get("type"))) datetime.add(k);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        src.forEach((k, v) -> {
            if (NOT_COPIED.contains(k) || readonly.contains(k) || k.startsWith("sys_")) return;
            if (datetime.contains(k)) { clearedDates.add(k); out.put(k, ""); return; }
            out.put(k, v);
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> x) x.forEach((k, v) -> m.put(String.valueOf(k), v));
        return m;
    }

    public record TemplateSummary(String name, String description, List<String> matchKeywords, int taskCount) {}
    public record DraftResult(ChangeDraft draft, Map<String, String> fieldSources, List<String> notes) {}

    @Tool(name = "list_templates", description = "Change templates with their keywords.")
    public List<TemplateSummary> listTemplates() {
        List<TemplateSummary> out = new ArrayList<>();
        for (ChangeTemplate t : templates.listTemplates())
            out.add(new TemplateSummary(t.name(), t.description(), t.matchKeywords(), t.tasks() == null ? 0 : t.tasks().size()));
        return out;
    }

    @Tool(name = "get_template", description = "One template in full.")
    public ChangeTemplate getTemplate(String name) { return templates.getTemplate(name.trim()); }

    @Tool(name = "build_draft", description = "Draft from a template: default fields, tasks (timed when plannedStart is given), description with <TODO> sections to fill.")
    public DraftResult buildDraft(
            String templateName,
            @ToolParam(description = "Comma separated", required = false) String ciNames,
            @ToolParam(description = "yyyy-MM-dd HH:mm:ss", required = false) String plannedStart,
            @ToolParam(description = "For the title", required = false) String summary) {
        List<CiInfo> cis = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (String n : (ciNames == null ? "" : ciNames).split(",")) {
            String q = n.trim();
            if (q.isEmpty()) continue;
            List<CiInfo> found = inv.lookup(q);
            if (found.isEmpty()) notes.add("Server not in inventory: " + q + " (draft still built; confirm the name with the user)");
            else { if (found.size() > 1) notes.add("Several servers match '" + q + "', used " + found.get(0).name()); cis.add(found.get(0)); }
        }
        ChangeTemplate t = templates.getTemplate(templateName.trim());
        ChangeDraft draft = templates.buildDraft(t, cis, plannedStart == null ? "" : plannedStart.trim(), summary);
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(t.titleField(), "template pattern + summary");
        sources.put(t.descriptionField(), "template sections, all TODO");
        sources.put("other fields", "template defaults; " + (cis.isEmpty() ? "no server resolved" : "owner group / service from inventory"));
        if (plannedStart != null && !plannedStart.isBlank()) sources.put(t.startField() + " / " + t.endField(), "plannedStart + task durations");
        sources.put("tasks", "template sequence, times computed");
        if (!cis.isEmpty()) notes.add("Maintenance window: " + cis.stream().map(c -> c.name() + " " + inv.windowFor(c)).toList());
        return new DraftResult(draft, sources, notes);
    }
}
