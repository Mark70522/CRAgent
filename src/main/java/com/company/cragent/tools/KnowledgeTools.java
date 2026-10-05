package com.company.cragent.tools;

import com.company.cragent.config.KnowledgeProperties;
import com.company.cragent.knowledge.ExampleStore;
import com.company.cragent.knowledge.Regression;
import com.company.cragent.model.ChangeRecord;
import com.company.cragent.servicenow.ServiceNowClient;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The learning side: rules (hard-rules.yaml / soft-rules.md), archived examples (approved and rejected)
 * and the regression that keeps the two consistent. Plain files under knowledge/, tracked by git.
 */
@Component
public class KnowledgeTools {

    private final KnowledgeProperties props;
    private final RuleEngine rules;
    private final ServiceNowClient sn;
    private final ExampleStore examples;
    private final Regression regression;

    public KnowledgeTools(KnowledgeProperties props, RuleEngine rules, ServiceNowClient sn, ExampleStore examples, Regression regression) {
        this.props = props;
        this.rules = rules;
        this.sn = sn;
        this.examples = examples;
        this.regression = regression;
    }

    public record Rules(List<HardRule> hardRules, String softRules) {}

    @Tool(name = "read_rules", description = "Hard rules (machine-checked) and soft rules (yours to judge). Read before drafting or reviewing.")
    public Rules readRules() {
        return new Rules(rules.loadRules(), readOrEmpty(props.rulesDir().resolve("soft-rules.md")));
    }

    @Tool(name = "add_hard_rule", description = "Add a hard rule after the user confirmed it; returns the regression result (an approved example failing = rule too strict).")
    public String addHardRule(
            @ToolParam(description = "One rule as YAML: id, description, type, field …") String ruleYaml,
            @ToolParam(description = "Who asked / which rejection") String reason) {
        String normalized = "  - " + ruleYaml.strip().replace("\n", "\n    ");
        List<HardRule> parsed = RuleEngine.parseRules(new ByteArrayInputStream(("rules:\n" + normalized + "\n").getBytes(StandardCharsets.UTF_8)));
        if (parsed.size() != 1 || parsed.get(0).id() == null || parsed.get(0).type() == null)
            throw new IllegalArgumentException("Snippet must be exactly one rule with at least id and type");
        HardRule r = parsed.get(0);
        if (rules.loadRules().stream().anyMatch(x -> r.id().equalsIgnoreCase(x.id()))) throw new IllegalArgumentException("Rule id already exists: " + r.id());
        append(props.rulesDir().resolve("hard-rules.yaml"), "\n" + normalized + "\n");
        appendChangelog("hard", r.id(), r.description(), reason);
        return "Added hard rule " + r.id() + ". Regression: " + Regression.summary(regression.run());
    }

    @Tool(name = "add_soft_rule", description = "Add a judgement rule after the user confirmed it.")
    public String addSoftRule(
            @ToolParam(description = "SR-010") String id,
            String text,
            @ToolParam(description = "Who asked / which rejection") String reason) {
        append(props.rulesDir().resolve("soft-rules.md"), "\n### " + id + "\n" + text.strip() + "\n\n_Why:_ " + reason.strip() + " (" + LocalDate.now() + ")\n");
        appendChangelog("soft", id, text.strip().lines().findFirst().orElse(""), reason);
        return "Added soft rule " + id;
    }

    @Tool(name = "save_example", description = "Archive an approved CR as a writing example and regression case.")
    public String saveExample(String number,
                              @ToolParam(description = "Template name, e.g. os-patch") String category) {
        ChangeRecord rec = sn.getChange(number.trim());
        Path f = examples.saveApproved(rec, category);
        return "Saved " + rel(f) + ". Regression: " + Regression.summary(regression.run());
    }

    @Tool(name = "save_rejected", description = "Archive a rejected CR with the reason; rules must keep catching it.")
    public String saveRejected(String number,
                               @ToolParam(description = "As the approver gave it") String reason,
                               @ToolParam(description = "Rule ids that should fire, e.g. [HR-011]", required = false) List<String> expectedRules) {
        ChangeRecord rec = sn.getChange(number.trim());
        Path f = examples.saveRejected(rec, reason, expectedRules);
        return "Saved " + rel(f) + ". Regression: " + Regression.summary(regression.run());
    }

    @Tool(name = "eval_rules", description = "Rule regression: approved examples must pass, rejected ones must fail. Run after changing rules.")
    public Map<String, Object> evalRules() {
        Regression.Report rep = regression.run();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", rep.ok());
        m.put("approvedChecked", rep.approvedChecked());
        m.put("rejectedChecked", rep.rejectedChecked());
        m.put("failures", rep.failures());
        return m;
    }

    @Tool(name = "list_examples", description = "Archived approved examples by category and rejected CRs, with paths.")
    public Map<String, List<String>> listExamples() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put("approved", examples.approved().stream().map(e -> e.relative(examples.root()) + " : " + e.category() + " : " + e.fields().getOrDefault("short_description", "")).toList());
        out.put("rejected", examples.rejected().stream().map(e -> e.relative(examples.root()) + " : " + e.reason()).toList());
        return out;
    }

    @Tool(name = "read_example", description = "One archived example by its path.")
    public String readExample(@ToolParam(description = "Path from list_examples") String relativePath) {
        String s = examples.read(relativePath);
        return s.isEmpty() ? "(no such file: " + relativePath + ")" : s;
    }

    // ------------------------------------------------------------------ helpers

    private String rel(Path f) { return props.knowledgeDir().relativize(f).toString().replace('\\', '/'); }

    private void appendChangelog(String kind, String id, String summary, String reason) {
        append(props.rulesDir().resolve("changelog.md"), "- " + LocalDate.now() + " | " + kind + " | " + id + " | " + nz(summary) + " | " + nz(reason) + "\n");
    }
    private static void append(Path file, String text) {
        // under the directory lock: Copilot's process and the page's process may both add rules
        com.company.cragent.util.DirLock.run(file.getParent(), () -> {
            try { Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (IOException e) { throw new IllegalStateException("Cannot write " + file, e); }
        });
    }
    private static String readOrEmpty(Path p) {
        try { return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : ""; }
        catch (IOException e) { throw new IllegalStateException("Cannot read " + p, e); }
    }
    private static String nz(String s) { return s == null ? "" : s; }
}
