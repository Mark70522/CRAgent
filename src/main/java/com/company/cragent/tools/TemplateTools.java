package com.company.cragent.tools;

import com.company.cragent.inventory.CiDirectory;
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
    private final CiDirectory dir;

    public TemplateTools(TemplateService templates, CiDirectory dir) {
        this.templates = templates;
        this.dir = dir;
    }

    public record TemplateSummary(String name, String description, List<String> matchKeywords, int taskCount) {}

    public record DraftResult(ChangeDraft draft, Map<String, String> fieldSources, List<String> notes) {}

    @Tool(name = "list_templates", description = """
            List the change templates under knowledge/templates (os-patch, db-patch, app-release, ...).
            Pick the one whose match keywords fit the user's request; ask the user if none fits.""")
    public List<TemplateSummary> listTemplates() {
        List<TemplateSummary> out = new ArrayList<>();
        for (ChangeTemplate t : templates.listTemplates()) {
            out.add(new TemplateSummary(t.name(), t.description(), t.matchKeywords(),
                    t.tasks() == null ? 0 : t.tasks().size()));
        }
        return out;
    }

    @Tool(name = "get_template", description = "Full definition of one template: default fields, task sequence with durations, description sections.")
    public ChangeTemplate getTemplate(@ToolParam(description = "Template name") String name) {
        return templates.getTemplate(name.trim());
    }

    @Tool(name = "build_draft", description = """
            Build a change draft from a template: resolves the CIs in the CMDB, fills default fields, lays out the
            change tasks back-to-back from plannedStart with the template durations, computes the change window,
            and produces a description skeleton whose sections contain <TODO: ...> markers.
            The result tells you where each field came from (template / cmdb / computed). You must then replace
            every TODO with concrete content taken from the user's request and from similar historical changes,
            and run validate_draft before showing it to the user.""")
    public DraftResult buildDraft(
            @ToolParam(description = "Template name from list_templates") String templateName,
            @ToolParam(description = "Server names, comma separated") String ciNames,
            @ToolParam(description = "Planned start, yyyy-MM-dd HH:mm:ss") String plannedStart,
            @ToolParam(description = "One-line summary used in the title, e.g. '2026-10 Windows monthly security patches'") String summary) {
        List<CiInfo> cis = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (String n : ciNames.split(",")) {
            String q = n.trim();
            if (q.isEmpty()) continue;
            List<CiInfo> found = dir.lookup(q);
            if (found.isEmpty()) notes.add("Server not found in " + dir.sourceDescription() + ": " + q + " (draft still built, confirm the name with the user)");
            else if (found.size() > 1) {
                notes.add("Several CIs match '" + q + "', used the first: " + found.get(0).name());
                cis.add(found.get(0));
            } else cis.add(found.get(0));
        }
        ChangeTemplate t = templates.getTemplate(templateName.trim());
        ChangeDraft draft = templates.buildDraft(t, cis, plannedStart.trim(), summary);

        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("short_description", "template pattern + user summary");
        sources.put("description", "template sections, all TODO");
        sources.put("type/category/risk/impact", "template defaults");
        sources.put("cmdb_ci", cis.isEmpty() ? "NOT RESOLVED" : dir.sourceDescription());
        sources.put("assignment_group", cis.isEmpty() ? "template" : "owner group of " + cis.get(0).name() + " in " + dir.sourceDescription());
        sources.put("start_date/end_date", "computed from plannedStart + task durations");
        sources.put("tasks", "template sequence, times computed");
        sources.put("justification/implementation_plan/backout_plan/test_plan", "template defaults or empty, must be written");
        if (cis.size() > 1) notes.add("Multiple CIs: cmdb_ci holds the first one; list all of them in the description and consider one change per CI if your process requires it.");
        return new DraftResult(draft, sources, notes);
    }
}
