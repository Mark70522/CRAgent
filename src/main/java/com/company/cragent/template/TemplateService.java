package com.company.cragent.template;

import com.company.cragent.config.KnowledgeProperties;
import com.company.cragent.model.ChangeDraft;
import com.company.cragent.model.CiInfo;
import com.company.cragent.model.TaskDraft;
import com.company.cragent.validation.RuleEngine;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Loads change templates and expands them into a ChangeDraft with a task timeline. */
@Component
public class TemplateService {

    private final Path templatesDir;

    @org.springframework.beans.factory.annotation.Autowired
    public TemplateService(KnowledgeProperties props) {
        this(props.templatesDir());
    }

    public TemplateService(Path templatesDir) {
        this.templatesDir = templatesDir;
    }

    public List<ChangeTemplate> listTemplates() {
        if (!Files.isDirectory(templatesDir)) return List.of();
        try (Stream<Path> files = Files.list(templatesDir)) {
            return files.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .sorted()
                    .map(this::load)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list " + templatesDir, e);
        }
    }

    public ChangeTemplate getTemplate(String name) {
        return listTemplates().stream()
                .filter(t -> t.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No template named '" + name + "'. Available: "
                        + listTemplates().stream().map(ChangeTemplate::name).toList()));
    }

    /**
     * Expand a template into a draft. Tasks are laid out back-to-back from plannedStart,
     * the change window ends when the last task ends (or after defaultDurationMinutes if longer).
     */
    public ChangeDraft buildDraft(ChangeTemplate t, List<CiInfo> cis, String plannedStart, String summary) {
        LocalDateTime start = RuleEngine.parse(plannedStart);
        if (start == null) throw new IllegalArgumentException("plannedStart must be yyyy-MM-dd HH:mm:ss, got: " + plannedStart);

        String ciList = cis.stream().map(CiInfo::name).collect(Collectors.joining(" "));
        String ownerGroup = cis.isEmpty() || cis.get(0).ownerGroup() == null ? "" : cis.get(0).ownerGroup();
        String env = cis.isEmpty() || cis.get(0).environment() == null ? "" : cis.get(0).environment();

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("ci_list", ciList);
        vars.put("ci_owner_group", ownerGroup);
        vars.put("environment", env);
        vars.put("summary", summary == null ? "" : summary);

        List<TaskDraft> tasks = new ArrayList<>();
        LocalDateTime cursor = start;
        for (ChangeTemplate.TaskTemplate tt : t.tasks() == null ? List.<ChangeTemplate.TaskTemplate>of() : t.tasks()) {
            int minutes = tt.durationMinutes() == null ? 30 : tt.durationMinutes();
            LocalDateTime end = cursor.plusMinutes(minutes);
            tasks.add(new TaskDraft(
                    interpolate(tt.shortDescription(), vars),
                    interpolate(tt.description(), vars),
                    tt.order(),
                    interpolate(tt.assignmentGroup() == null ? "{ci_owner_group}" : tt.assignmentGroup(), vars),
                    cursor.format(RuleEngine.SN_DATETIME),
                    end.format(RuleEngine.SN_DATETIME)));
            cursor = end;
        }
        LocalDateTime windowEnd = cursor;
        if (t.defaultDurationMinutes() != null && start.plusMinutes(t.defaultDurationMinutes()).isAfter(windowEnd)) {
            windowEnd = start.plusMinutes(t.defaultDurationMinutes());
        }

        StringBuilder desc = new StringBuilder();
        int i = 1;
        for (ChangeTemplate.Section s : t.descriptionSections() == null ? List.<ChangeTemplate.Section>of() : t.descriptionSections()) {
            desc.append(i++).append(". ").append(s.heading()).append('\n');
            desc.append("<TODO: ").append(s.hint() == null ? "" : s.hint()).append(">\n\n");
        }

        Map<String, String> f = t.fields() == null ? Map.of() : t.fields();
        Map<String, String> extra = new LinkedHashMap<>();
        f.forEach((k, v) -> { if (!STANDARD.contains(k)) extra.put(k, interpolate(v, vars)); });

        return new ChangeDraft(
                interpolate(t.shortDescriptionPattern() == null ? "{summary}" : t.shortDescriptionPattern(), vars).trim(),
                desc.toString().trim(),
                f.get("type"), f.get("category"), f.get("risk"), f.get("impact"), f.get("priority"),
                cis.isEmpty() ? null : cis.get(0).name(),
                f.getOrDefault("assignment_group", ownerGroup),
                f.get("assigned_to"), f.get("requested_by"),
                start.format(RuleEngine.SN_DATETIME), windowEnd.format(RuleEngine.SN_DATETIME),
                f.get("justification"), f.get("implementation_plan"), f.get("risk_impact_analysis"),
                f.get("backout_plan"), f.get("test_plan"),
                extra, tasks);
    }

    private static final List<String> STANDARD = List.of("type", "category", "risk", "impact", "priority",
            "assignment_group", "assigned_to", "requested_by", "justification", "implementation_plan",
            "risk_impact_analysis", "backout_plan", "test_plan");

    static String interpolate(String s, Map<String, String> vars) {
        if (s == null) return null;
        String out = s;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    ChangeTemplate load(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            Map<String, Object> m = new Yaml().load(in);
            List<ChangeTemplate.TaskTemplate> tasks = new ArrayList<>();
            if (m.get("tasks") instanceof List<?> l) {
                for (Object o : l) {
                    Map<String, Object> t = (Map<String, Object>) o;
                    tasks.add(new ChangeTemplate.TaskTemplate(intOrNull(t.get("order")), str(t.get("short_description")),
                            str(t.get("description")), intOrNull(t.get("duration_minutes")), str(t.get("assignment_group"))));
                }
            }
            List<ChangeTemplate.Section> sections = new ArrayList<>();
            if (m.get("description_sections") instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> sm) sections.add(new ChangeTemplate.Section(str(sm.get("heading")), str(sm.get("hint"))));
                    else sections.add(new ChangeTemplate.Section(String.valueOf(o), ""));
                }
            }
            Map<String, String> fields = new LinkedHashMap<>();
            if (m.get("fields") instanceof Map<?, ?> fm) fm.forEach((k, v) -> fields.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
            String name = m.get("name") == null
                    ? file.getFileName().toString().replaceAll("\\.ya?ml$", "")
                    : String.valueOf(m.get("name"));
            return new ChangeTemplate(name, str(m.get("description")),
                    m.get("match_keywords") instanceof List<?> k ? k.stream().map(String::valueOf).toList() : List.of(),
                    fields, str(m.get("short_description_pattern")), intOrNull(m.get("default_duration_minutes")),
                    tasks, sections);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read template " + file, e);
        }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static Integer intOrNull(Object o) { return o == null ? null : Integer.valueOf(String.valueOf(o)); }
}
