package com.company.cragent.tools;

import com.company.cragent.config.KnowledgeProperties;
import com.company.cragent.model.ChangeRecord;
import com.company.cragent.servicenow.ServiceNowGateway;
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
 * Everything is plain files under knowledge/, so people can edit them too and git tracks who changed what.
 */
@Component
public class KnowledgeTools {

    private final KnowledgeProperties props;
    private final RuleEngine rules;
    private final ServiceNowGateway sn;

    public KnowledgeTools(KnowledgeProperties props, RuleEngine rules, ServiceNowGateway sn) {
        this.props = props;
        this.rules = rules;
        this.sn = sn;
    }

    public record Rules(List<HardRule> hardRules, String softRules) {}

    @Tool(name = "read_rules", description = """
            Return the current rule set: hard rules (machine-checked, from hard-rules.yaml) and soft rules
            (judged by you, from soft-rules.md). Read them at the start of every create or review.""")
    public Rules readRules() {
        return new Rules(rules.loadRules(), readOrEmpty(props.rulesDir().resolve("soft-rules.md")));
    }

    @Tool(name = "add_hard_rule", description = """
            Append a machine-checkable rule to hard-rules.yaml and record it in changelog.md. Pass ONE rule as a
            YAML mapping (id, description, type, field, ... see existing file). The snippet is parsed first and
            rejected if invalid. Only call this after the user confirmed the rule.""")
    public String addHardRule(
            @ToolParam(description = "YAML for one rule, e.g. \"id: HR-010\\ndescription: ...\\ntype: required\\nfield: backout_plan\"") String ruleYaml,
            @ToolParam(description = "Why this rule exists: who asked for it / which rejection triggered it") String reason) {
        String normalized = "  - " + ruleYaml.strip().replace("\n", "\n    ");
        List<HardRule> parsed = RuleEngine.parseRules(new ByteArrayInputStream(
                ("rules:\n" + normalized + "\n").getBytes(StandardCharsets.UTF_8)));
        if (parsed.size() != 1 || parsed.get(0).id() == null || parsed.get(0).type() == null) {
            throw new IllegalArgumentException("Snippet must be exactly one rule with at least id and type");
        }
        HardRule r = parsed.get(0);
        if (rules.loadRules().stream().anyMatch(x -> r.id().equalsIgnoreCase(x.id()))) {
            throw new IllegalArgumentException("Rule id already exists: " + r.id());
        }
        append(props.rulesDir().resolve("hard-rules.yaml"), "\n" + normalized + "\n");
        appendChangelog("hard", r.id(), r.description(), reason);
        return "Added hard rule " + r.id();
    }

    @Tool(name = "add_soft_rule", description = """
            Append a rule that needs judgement (wording, level of detail, tone) to soft-rules.md and record it
            in changelog.md. Only call this after the user confirmed the rule.""")
    public String addSoftRule(
            @ToolParam(description = "Rule id like SR-010") String id,
            @ToolParam(description = "The rule, one or two sentences, with an example of good and bad if useful") String text,
            @ToolParam(description = "Why: who asked / which rejection") String reason) {
        append(props.rulesDir().resolve("soft-rules.md"),
                "\n### " + id + "\n" + text.strip() + "\n\n_Why:_ " + reason.strip() + " (" + LocalDate.now() + ")\n");
        appendChangelog("soft", id, text.strip().lines().findFirst().orElse(""), reason);
        return "Added soft rule " + id;
    }

    @Tool(name = "save_example", description = """
            Archive an approved change request under knowledge/examples/<category>/ as a gold-standard example
            for future drafts. Use for changes that passed approval without comments.""")
    public String saveExample(
            @ToolParam(description = "Change number") String number,
            @ToolParam(description = "Category folder, e.g. os-patch, db-patch, app-release") String category) {
        ChangeRecord r = sn.getChange(number.trim());
        Path file = props.examplesDir().resolve(safe(category)).resolve(r.number() + ".md");
        write(file, render(r, null));
        return "Saved " + file;
    }

    @Tool(name = "save_rejected", description = """
            Archive a rejected change request under knowledge/rejected/ together with the rejection reason.
            These are the negative examples the learn-rules skill mines for new rules.""")
    public String saveRejected(
            @ToolParam(description = "Change number") String number,
            @ToolParam(description = "Rejection reason as given by the approver or the boss") String reason) {
        ChangeRecord r = sn.getChange(number.trim());
        Path file = props.rejectedDir().resolve(r.number() + ".md");
        write(file, render(r, reason));
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

    private List<String> listMd(Path dir) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(p -> p.toString().endsWith(".md") && !p.getFileName().toString().equalsIgnoreCase("README.md"))
                    .sorted()
                    .forEach(p -> out.add(props.knowledgeDir().relativize(p).toString().replace('\\', '/')
                            + " : " + readOrEmpty(p).lines().findFirst().orElse("")));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static String render(ChangeRecord r, String rejectionReason) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(r.number()).append(" - ").append(r.field("short_description")).append("\n\n");
        if (rejectionReason != null) sb.append("**REJECTED:** ").append(rejectionReason).append("\n\n");
        sb.append("## Fields\n\n");
        for (String k : List.of("type", "category", "risk", "impact", "priority", "cmdb_ci", "assignment_group",
                "start_date", "end_date", "approval", "close_code", "state")) {
            sb.append("- ").append(k).append(": ").append(nz(r.field(k))).append('\n');
        }
        for (Map.Entry<String, String> e : r.fields().entrySet()) {
            if (e.getKey().startsWith("u_") && e.getValue() != null && !e.getValue().isBlank())
                sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        for (String k : List.of("description", "justification", "implementation_plan", "risk_impact_analysis", "backout_plan", "test_plan")) {
            sb.append("\n## ").append(k).append("\n\n").append(nz(r.field(k))).append('\n');
        }
        sb.append("\n## Tasks\n\n");
        for (Map<String, String> t : r.tasks()) {
            sb.append("- [").append(nz(t.get("order"))).append("] ").append(nz(t.get("short_description")))
                    .append(" | ").append(nz(t.get("planned_start_date"))).append(" -> ").append(nz(t.get("planned_end_date")))
                    .append(" | ").append(nz(t.get("assignment_group"))).append('\n');
        }
        sb.append("\n## Approvals\n\n");
        for (Map<String, String> a : r.approvals()) {
            sb.append("- ").append(nz(a.get("approver"))).append(": ").append(nz(a.get("state")));
            if (a.get("comments") != null && !a.get("comments").isBlank()) sb.append(" - ").append(a.get("comments"));
            sb.append('\n');
        }
        return sb.toString();
    }

    private void appendChangelog(String kind, String id, String summary, String reason) {
        append(props.rulesDir().resolve("changelog.md"),
                "- " + LocalDate.now() + " | " + kind + " | " + id + " | " + nz(summary) + " | " + nz(reason) + "\n");
    }

    private static void append(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file, e);
        }
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file, e);
        }
    }

    private static String readOrEmpty(Path p) {
        try { return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : ""; }
        catch (IOException e) { throw new IllegalStateException("Cannot read " + p, e); }
    }

    private static String safe(String s) { return s.trim().toLowerCase().replaceAll("[^a-z0-9._-]", "-"); }
    private static String nz(String s) { return s == null ? "" : s; }
}
