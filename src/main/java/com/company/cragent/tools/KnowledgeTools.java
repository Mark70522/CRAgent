package com.company.cragent.tools;

import com.company.cragent.config.KnowledgeProperties;
import com.company.cragent.validation.HardRule;
import com.company.cragent.validation.RuleEngine;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The learning side: read and extend the rule files, archive good and rejected changes as examples.
 * Plain files under knowledge/, so people can edit them too and git tracks who changed what.
 */
@Component
public class KnowledgeTools {

    private final KnowledgeProperties props;
    private final RuleEngine rules;
    private final ServiceNowTools sn;

    public KnowledgeTools(KnowledgeProperties props, RuleEngine rules, ServiceNowTools sn) {
        this.props = props;
        this.rules = rules;
        this.sn = sn;
    }

    public record Rules(List<HardRule> hardRules, String softRules) {}

    @Tool(name = "read_rules", description = "Current rule set: hard rules (machine-checked) and soft rules (judged by you). Read at the start of every create or review.")
    public Rules readRules() {
        return new Rules(rules.loadRules(), readOrEmpty(props.rulesDir().resolve("soft-rules.md")));
    }

    @Tool(name = "add_hard_rule", description = """
            Append one machine-checkable rule (YAML mapping: id, description, type, field, ...) to hard-rules.yaml
            and record it in changelog.md. Parsed first, rejected if invalid or if the id exists. Only after the user confirmed.""")
    public String addHardRule(
            @ToolParam(description = "YAML for one rule, e.g. \"id: HR-020\\ndescription: ...\\ntype: required\\nfield: backout_plan\"") String ruleYaml,
            @ToolParam(description = "Why: who asked / which rejection") String reason) {
        String normalized = "  - " + ruleYaml.strip().replace("\n", "\n    ");
        List<HardRule> parsed = RuleEngine.parseRules(new ByteArrayInputStream(("rules:\n" + normalized + "\n").getBytes(StandardCharsets.UTF_8)));
        if (parsed.size() != 1 || parsed.get(0).id() == null || parsed.get(0).type() == null)
            throw new IllegalArgumentException("Snippet must be exactly one rule with at least id and type");
        HardRule r = parsed.get(0);
        if (rules.loadRules().stream().anyMatch(x -> r.id().equalsIgnoreCase(x.id()))) throw new IllegalArgumentException("Rule id already exists: " + r.id());
        append(props.rulesDir().resolve("hard-rules.yaml"), "\n" + normalized + "\n");
        appendChangelog("hard", r.id(), r.description(), reason);
        return "Added hard rule " + r.id();
    }

    @Tool(name = "add_soft_rule", description = "Append a judgement rule (wording, level of detail) to soft-rules.md and record it in changelog.md. Only after the user confirmed.")
    public String addSoftRule(
            @ToolParam(description = "Rule id like SR-010") String id,
            @ToolParam(description = "The rule, one or two sentences") String text,
            @ToolParam(description = "Why: who asked / which rejection") String reason) {
        append(props.rulesDir().resolve("soft-rules.md"), "\n### " + id + "\n" + text.strip() + "\n\n_Why:_ " + reason.strip() + " (" + LocalDate.now() + ")\n");
        appendChangelog("soft", id, text.strip().lines().findFirst().orElse(""), reason);
        return "Added soft rule " + id;
    }

    @Tool(name = "save_example", description = "Archive an approved change (read through get-change) under knowledge/examples/<category>/ as a gold example for future drafts.")
    public String saveExample(@ToolParam(description = "Change number") String number,
                              @ToolParam(description = "Category folder, e.g. os-patch, db-patch, app-release") String category) {
        Map<String, Object> rec = sn.getChange(number.trim());
        Path file = props.examplesDir().resolve(safe(category)).resolve(number.trim() + ".md");
        write(file, render(number.trim(), rec, null));
        return "Saved " + file;
    }

    @Tool(name = "save_rejected", description = "Archive a rejected change under knowledge/rejected/ with the rejection reason; the learn-rules skill mines these.")
    public String saveRejected(@ToolParam(description = "Change number") String number,
                               @ToolParam(description = "Rejection reason as given by the approver or the boss") String reason) {
        Map<String, Object> rec = sn.getChange(number.trim());
        Path file = props.rejectedDir().resolve(number.trim() + ".md");
        write(file, render(number.trim(), rec, reason));
        return "Saved " + file;
    }

    @Tool(name = "list_examples", description = "List archived example and rejected change files with their first line.")
    public Map<String, List<String>> listExamples() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put("examples", listMd(props.examplesDir()));
        out.put("rejected", listMd(props.rejectedDir()));
        return out;
    }

    @Tool(name = "read_example", description = "Read one archived example or rejected file by its path as returned by list_examples.")
    public String readExample(@ToolParam(description = "Relative path, e.g. examples/os-patch/CHG0030001.md") String relativePath) {
        Path p = props.knowledgeDir().resolve(relativePath).normalize();
        if (!p.startsWith(props.knowledgeDir().normalize())) throw new IllegalArgumentException("Path outside knowledge dir");
        return readOrEmpty(p);
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static String render(String number, Map<String, Object> rec, String rejectionReason) {
        Map<String, String> f = (Map<String, String>) rec.get("fields");
        List<Map<String, String>> tasks = (List<Map<String, String>>) rec.get("tasks");
        StringBuilder sb = new StringBuilder("# ").append(number).append("\n\n");
        if (rejectionReason != null) sb.append("**REJECTED:** ").append(rejectionReason).append("\n\n");
        sb.append("## Fields\n\n");
        f.forEach((k, v) -> {
            if (v == null || v.isBlank()) return;
            if (v.length() > 80 || v.contains("\n")) sb.append("### ").append(k).append("\n\n").append(v).append("\n\n");
            else sb.append("- ").append(k).append(": ").append(v).append('\n');
        });
        if (tasks != null && !tasks.isEmpty()) {
            sb.append("\n## Tasks\n\n");
            for (Map<String, String> t : tasks) sb.append("- ").append(t).append('\n');
        }
        return sb.toString();
    }

    private List<String> listMd(Path dir) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(p -> p.toString().endsWith(".md") && !p.getFileName().toString().equalsIgnoreCase("README.md")).sorted()
                    .forEach(p -> out.add(props.knowledgeDir().relativize(p).toString().replace('\\', '/') + " : " + readOrEmpty(p).lines().findFirst().orElse("")));
        } catch (IOException e) { throw new IllegalStateException(e); }
        return out;
    }

    private void appendChangelog(String kind, String id, String summary, String reason) {
        append(props.rulesDir().resolve("changelog.md"), "- " + LocalDate.now() + " | " + kind + " | " + id + " | " + nz(summary) + " | " + nz(reason) + "\n");
    }
    private static void append(Path file, String text) {
        try { Files.createDirectories(file.getParent()); Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException e) { throw new IllegalStateException("Cannot write " + file, e); }
    }
    private static void write(Path file, String text) {
        try { Files.createDirectories(file.getParent()); Files.writeString(file, text, StandardCharsets.UTF_8); }
        catch (IOException e) { throw new IllegalStateException("Cannot write " + file, e); }
    }
    private static String readOrEmpty(Path p) {
        try { return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : ""; }
        catch (IOException e) { throw new IllegalStateException("Cannot read " + p, e); }
    }
    private static String safe(String s) { return s.trim().toLowerCase().replaceAll("[^a-z0-9._-]", "-"); }
    private static String nz(String s) { return s == null ? "" : s; }
}
