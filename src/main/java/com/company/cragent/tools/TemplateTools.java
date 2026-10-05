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

    @Tool(name = "draft_from_change", description = """
            Start a new CR from an old one ("照着 CHG0031234 建一张"): copies its fields and tasks with their JSON
            types kept, drops what must not be copied (number, state, approval, sys_* and fields marked read-only in
            the field catalog), and clears every date-time field so no old date slips into the new CR. Reads the
            local copy unless live=true (or there is none). Then: set title, times and the description for this
            change, validate_draft, show the user, create_change.""")
    public DraftResult draftFromChange(
            @ToolParam(description = "The old change number, e.g. CHG0031234") String number,
            @ToolParam(description = "true = read it from the interface now instead of the local copy", required = false) Boolean live) {
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

    @Tool(name = "list_templates", description = "The change templates under knowledge/templates. Pick the one whose keywords fit the request; ask if none fits.")
    public List<TemplateSummary> listTemplates() {
        List<TemplateSummary> out = new ArrayList<>();
        for (ChangeTemplate t : templates.listTemplates())
            out.add(new TemplateSummary(t.name(), t.description(), t.matchKeywords(), t.tasks() == null ? 0 : t.tasks().size()));
        return out;
    }

    @Tool(name = "get_template", description = "Full definition of one template: default fields, task sequence with durations, description sections.")
    public ChangeTemplate getTemplate(@ToolParam(description = "Template name") String name) { return templates.getTemplate(name.trim()); }

    @Tool(name = "build_draft", description = """
            Build a draft from a template: resolves the servers in the inventory (if any), fills default fields, (if plannedStart is given) lays
            the tasks out back to back and computes the change window, and puts a section skeleton with
            <TODO: ...> markers into the description field. Field names are exactly what the template says (your
            interface's names). Replace every TODO with real content, then validate_draft before showing the user.""")
    public DraftResult buildDraft(
            @ToolParam(description = "Template name from list_templates") String templateName,
            @ToolParam(description = "Server names, comma separated; optional", required = false) String ciNames,
            @ToolParam(description = "Planned start, yyyy-MM-dd HH:mm:ss; optional, without it no times are filled", required = false) String plannedStart,
            @ToolParam(description = "One-line summary for the title; optional, without it the title is a TODO", required = false) String summary) {
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
