package com.company.cragent.tools;

import com.company.cragent.inventory.Inventory;
import com.company.cragent.model.ChangeDraft;
import com.company.cragent.model.CiInfo;
import com.company.cragent.template.ChangeTemplate;
import com.company.cragent.template.TemplateService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TemplateTools {

    private final TemplateService templates;
    private final Inventory inv;

    public TemplateTools(TemplateService templates, Inventory inv) {
        this.templates = templates;
        this.inv = inv;
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
            Build a draft from a template: resolves the servers in the inventory, fills default fields, lays the
            tasks out back to back from plannedStart, computes the change window, and puts a section skeleton with
            <TODO: ...> markers into the description field. Field names are exactly what the template says (your
            interface's names). Replace every TODO with real content, then validate_draft before showing the user.""")
    public DraftResult buildDraft(
            @ToolParam(description = "Template name from list_templates") String templateName,
            @ToolParam(description = "Server names, comma separated") String ciNames,
            @ToolParam(description = "Planned start, yyyy-MM-dd HH:mm:ss") String plannedStart,
            @ToolParam(description = "One-line summary for the title") String summary) {
        List<CiInfo> cis = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (String n : ciNames.split(",")) {
            String q = n.trim();
            if (q.isEmpty()) continue;
            List<CiInfo> found = inv.lookup(q);
            if (found.isEmpty()) notes.add("Server not in inventory: " + q + " (draft still built; confirm the name with the user)");
            else { if (found.size() > 1) notes.add("Several servers match '" + q + "', used " + found.get(0).name()); cis.add(found.get(0)); }
        }
        ChangeTemplate t = templates.getTemplate(templateName.trim());
        ChangeDraft draft = templates.buildDraft(t, cis, plannedStart.trim(), summary);
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(t.titleField(), "template pattern + summary");
        sources.put(t.descriptionField(), "template sections, all TODO");
        sources.put("other fields", "template defaults; " + (cis.isEmpty() ? "no server resolved" : "owner group / service from inventory"));
        sources.put(t.startField() + " / " + t.endField(), "plannedStart + task durations");
        sources.put("tasks", "template sequence, times computed");
        if (!cis.isEmpty()) notes.add("Maintenance window: " + cis.stream().map(c -> c.name() + " " + inv.windowFor(c)).toList());
        return new DraftResult(draft, sources, notes);
    }
}
