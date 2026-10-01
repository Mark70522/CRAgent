package com.company.cragent.template;

import com.company.cragent.config.KnowledgeProperties;
import com.company.cragent.model.ChangeDraft;
import com.company.cragent.model.CiInfo;
import com.company.cragent.validation.RuleEngine;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    public TemplateService(KnowledgeProperties props) { this(props.templatesDir()); }

    public TemplateService(Path templatesDir) { this.templatesDir = templatesDir; }

    public List<ChangeTemplate> listTemplates() {
        if (!Files.isDirectory(templatesDir)) return List.of();
        try (Stream<Path> files = Files.list(templatesDir)) {
            return files.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml")).sorted().map(this::load).toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list " + templatesDir, e);
        }
    }

    public ChangeTemplate getTemplate(String name) {
        return listTemplates().stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No template named '" + name + "'. Available: "
                        + listTemplates().stream().map(ChangeTemplate::name).toList()));
    }

    /**
     * Expand a template: default fields, title from the pattern, description skeleton with TODO markers,
     * tasks laid out back to back from plannedStart, change window from first task to last (or default duration).
     */
    public ChangeDraft buildDraft(ChangeTemplate t, List<CiInfo> cis, String plannedStart, String summary) {
        LocalDateTime start = RuleEngine.parse(plannedStart);
        if (start == null) throw new IllegalArgumentException("plannedStart must be yyyy-MM-dd HH:mm:ss, got: " + plannedStart);

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("ci_list", cis.stream().map(CiInfo::name).collect(Collectors.joining(" ")));
        vars.put("ci", cis.isEmpty() ? "" : cis.get(0).name());
        vars.put("ci_owner_group", cis.isEmpty() || cis.get(0).ownerGroup() == null ? "" : cis.get(0).ownerGroup());
        vars.put("environment", cis.isEmpty() || cis.get(0).environment() == null ? "" : cis.get(0).environment());
        vars.put("service", cis.isEmpty() || cis.get(0).service() == null ? "" : cis.get(0).service());
        vars.put("summary", summary == null ? "" : summary);

        Map<String, Object> fields = new LinkedHashMap<>();
        if (t.fields() != null) t.fields().forEach((k, v) -> fields.put(k, interpolate(v, vars)));
        fields.put(t.titleField(), interpolate(t.shortDescriptionPattern() == null ? "${summary}" : t.shortDescriptionPattern(), vars).trim());

        StringBuilder desc = new StringBuilder();
        int i = 1;
        for (ChangeTemplate.Section s : t.descriptionSections() == null ? List.<ChangeTemplate.Section>of() : t.descriptionSections()) {
            desc.append(i++).append(". ").append(s.heading()).append('\n').append("<TODO: ").append(s.hint() == null ? "" : s.hint()).append(">\n\n");
        }
        if (desc.length() > 0) fields.put(t.descriptionField(), desc.toString().trim());

        List<Map<String, Object>> tasks = new ArrayList<>();
        LocalDateTime cursor = start;
        for (ChangeTemplate.TaskTemplate tt : t.tasks() == null ? List.<ChangeTemplate.TaskTemplate>of() : t.tasks()) {
            int minutes = tt.durationMinutes() == null ? 30 : tt.durationMinutes();
            LocalDateTime end = cursor.plusMinutes(minutes);
            Map<String, Object> task = new LinkedHashMap<>();
            if (tt.fields() != null) tt.fields().forEach((k, v) -> task.put(k, interpolate(v, vars)));
            task.put(t.taskStartField(), cursor.format(RuleEngine.SN_DATETIME));
            task.put(t.taskEndField(), end.format(RuleEngine.SN_DATETIME));
            tasks.add(task);
            cursor = end;
        }
        LocalDateTime windowEnd = cursor;
        if (t.defaultDurationMinutes() != null && start.plusMinutes(t.defaultDurationMinutes()).isAfter(windowEnd))
            windowEnd = start.plusMinutes(t.defaultDurationMinutes());
        fields.put(t.startField(), start.format(RuleEngine.SN_DATETIME));
        fields.put(t.endField(), windowEnd.format(RuleEngine.SN_DATETIME));

        return new ChangeDraft(fields, tasks);
    }

    /** ${name} and {name} both work, so older templates keep working. */
    static String interpolate(String s, Map<String, String> vars) {
        if (s == null) return null;
        String out = s;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue();
            out = out.replace("${" + e.getKey() + "}", v).replace("{" + e.getKey() + "}", v);
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
                    Map<String, String> f = new LinkedHashMap<>();
                    Integer minutes = null;
                    for (Map.Entry<String, Object> e : t.entrySet()) {
                        if ("duration_minutes".equals(e.getKey())) minutes = intOrNull(e.getValue());
                        else f.put(e.getKey(), e.getValue() == null ? "" : String.valueOf(e.getValue()));
                    }
                    tasks.add(new ChangeTemplate.TaskTemplate(f, minutes));
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
            String name = m.get("name") == null ? file.getFileName().toString().replaceAll("\\.ya?ml$", "") : String.valueOf(m.get("name"));
            return new ChangeTemplate(name, str(m.get("description")),
                    m.get("match_keywords") instanceof List<?> k ? k.stream().map(String::valueOf).toList() : List.of(),
                    fields, str(m.get("title_field")), str(m.get("short_description_pattern")), str(m.get("description_field")),
                    str(m.get("start_field")), str(m.get("end_field")), intOrNull(m.get("default_duration_minutes")),
                    str(m.get("task_start_field")), str(m.get("task_end_field")), tasks, sections);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read template " + file, e);
        }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static Integer intOrNull(Object o) { return o == null ? null : Integer.valueOf(String.valueOf(o)); }
}
