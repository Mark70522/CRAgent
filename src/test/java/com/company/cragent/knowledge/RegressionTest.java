package com.company.cragent.knowledge;

import com.company.cragent.config.KnowledgeProperties;
import com.company.cragent.model.ChangeRecord;
import com.company.cragent.validation.RuleEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RegressionTest {

    @TempDir Path tmp;

    /** The real rules, a throw-away knowledge dir. */
    Regression regression(Path knowledgeDir) {
        return new Regression(new ExampleStore(new KnowledgeProperties(knowledgeDir)), new RuleEngine(Path.of("knowledge/rules/hard-rules.yaml")));
    }

    static ChangeRecord good(String number) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("number", number);
        f.put("short_description", "[PATCH] srv-app-01 - 2026-10 Windows monthly security patches");
        f.put("description", "1. 变更对象\nsrv-app-01 (prod, Windows Server 2019, Order Portal application server)\n\n2. 补丁清单及来源\n2026-10 Microsoft monthly security baseline from WSUS\n\n3. 影响范围\nOrder Portal front end unavailable for about 45 minutes, business owner informed\n\n4. 执行步骤\nsee change tasks\n\n5. 验证方法\nIIS returns 200, health check OK\n\n6. 回退方案\nrestore the VMware snapshot, about 20 minutes, rehearsed");
        f.put("type", "normal"); f.put("risk", "Moderate"); f.put("cmdb_ci", "srv-app-01"); f.put("assignment_group", "Wintel Ops");
        f.put("start_date", "2026-10-11 01:00:00"); f.put("end_date", "2026-10-11 05:00:00");
        f.put("justification", "Security compliance"); f.put("implementation_plan", "See tasks");
        f.put("backout_plan", "Restore snapshot, 20 min"); f.put("test_plan", "Health checks");
        List<Map<String, Object>> tasks = List.of(
                Map.of("short_description", "Pre-check", "order", "10", "assignment_group", "Wintel Ops", "planned_start_date", "2026-10-11 01:00:00", "planned_end_date", "2026-10-11 01:30:00"),
                Map.of("short_description", "Patch", "order", "20", "assignment_group", "Wintel Ops", "planned_start_date", "2026-10-11 01:30:00", "planned_end_date", "2026-10-11 03:30:00"));
        return new ChangeRecord(number, f, tasks, new ObjectMapper().valueToTree(f));
    }

    @Test
    void approvedMustPassRejectedMustTrip() {
        ExampleStore store = new ExampleStore(new KnowledgeProperties(tmp));
        Regression reg = regression(tmp);

        store.saveApproved(good("CHG0001"), "os-patch");
        ChangeRecord bad = good("CHG0002");
        bad.fields().put("short_description", "patch servers");
        bad.fields().remove("backout_plan");
        store.saveRejected(bad, "title not tagged, no backout plan", List.of("HR-001", "HR-011"));

        Regression.Report rep = reg.run();
        assertThat(rep.approvedChecked()).isEqualTo(1);
        assertThat(rep.rejectedChecked()).isEqualTo(1);
        assertThat(rep.ok()).as(Regression.summary(rep)).isTrue();

        // files are readable YAML with the number in them
        assertThat(Files.exists(tmp.resolve("examples/os-patch/CHG0001.yaml"))).isTrue();
        assertThat(store.read("rejected/CHG0002.yaml")).contains("outcome: rejected").contains("HR-011");
    }

    @Test
    void reportsFalsePositivesAndGaps() {
        ExampleStore store = new ExampleStore(new KnowledgeProperties(tmp));
        Regression reg = regression(tmp);

        // an "approved" example that actually breaks a rule -> false positive reported
        ChangeRecord strict = good("CHG0003");
        strict.fields().put("description", "too short");
        store.saveApproved(strict, "os-patch");
        // a "rejected" example that passes everything -> gap reported
        store.saveRejected(good("CHG0004"), "boss did not like the tone", List.of());
        // a rejected example whose expected rule does not fire -> reported
        ChangeRecord r = good("CHG0005");
        r.fields().put("short_description", "patch servers");
        store.saveRejected(r, "no tag", List.of("HR-999"));

        Regression.Report rep = reg.run();
        assertThat(rep.failures()).hasSize(3);
        assertThat(Regression.summary(rep)).contains("CHG0003").contains("too strict").contains("CHG0004").contains("not covered").contains("CHG0005").contains("HR-999");
    }

    /** The project's own knowledge/ directory must always be consistent: this is the gate for rule edits. */
    @Test
    void projectKnowledgeIsConsistent() {
        Regression.Report rep = regression(Path.of("knowledge")).run();
        assertThat(rep.ok()).as(Regression.summary(rep)).isTrue();
    }
}
