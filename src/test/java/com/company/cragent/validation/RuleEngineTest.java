package com.company.cragent.validation;

import com.company.cragent.model.Violation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {

    private final RuleEngine engine = new RuleEngine(Path.of("knowledge/rules/hard-rules.yaml"));

    // 2026-10-11 is a Sunday, inside the Sun 00:00-06:00 window of HR-018.
    private static Map<String, String> goodFields() {
        Map<String, String> f = new HashMap<>();
        f.put("short_description", "[PATCH] srv-app-01 - 2026-10 Windows monthly security patches");
        f.put("description", "1. 变更对象\nsrv-app-01 prod Windows Server 2019 Order Portal\n\n2. 补丁清单及来源\n2026-10 Microsoft monthly baseline from WSUS\n\n"
                + "3. 影响范围\nOrder Portal frontend unavailable about 45 minutes, business informed\n\n4. 执行步骤\nsee tasks\n\n"
                + "5. 验证方法\nIIS 200, health check OK, no critical events\n\n6. 回退方案\nrestore snapshot, 20 minutes");
        f.put("type", "normal");
        f.put("risk", "Moderate");
        f.put("cmdb_ci", "srv-app-01");
        f.put("assignment_group", "Wintel Ops");
        f.put("start_date", "2026-10-11 01:00:00");
        f.put("end_date", "2026-10-11 05:00:00");
        f.put("justification", "Security compliance");
        f.put("implementation_plan", "See tasks");
        f.put("backout_plan", "Restore snapshot, 20 min");
        f.put("test_plan", "Health checks");
        return f;
    }

    private static List<Map<String, String>> goodTasks() {
        return List.of(
                Map.of("short_description", "Pre-check", "order", "10", "assignment_group", "Wintel Ops",
                        "planned_start_date", "2026-10-11 01:00:00", "planned_end_date", "2026-10-11 01:30:00"),
                Map.of("short_description", "Patch", "order", "20", "assignment_group", "Wintel Ops",
                        "planned_start_date", "2026-10-11 01:30:00", "planned_end_date", "2026-10-11 03:30:00"));
    }

    @Test
    void goodDraftPasses() {
        List<Violation> v = engine.validate(goodFields(), goodTasks());
        assertThat(v).isEmpty();
    }

    @Test
    void catchesTheMockRejectionReasons() {
        Map<String, String> f = goodFields();
        f.put("short_description", "patch servers");
        f.put("description", "install patches this weekend");
        f.remove("backout_plan");
        f.put("start_date", "2026-10-10 10:00:00");   // Saturday, outside the window
        f.put("end_date", "2026-10-10 12:00:00");
        List<Violation> v = engine.validate(f, List.of());
        List<String> ids = v.stream().map(Violation::ruleId).toList();
        assertThat(ids).contains("HR-001", "HR-002", "HR-011", "HR-013", "HR-018");
    }

    @Test
    void rejectsUnfilledTodoAndOverlappingTasks() {
        Map<String, String> f = goodFields();
        f.put("description", f.get("description") + "\n<TODO: fill>");
        List<Map<String, String>> tasks = List.of(
                Map.of("short_description", "A", "order", "10", "assignment_group", "g",
                        "planned_start_date", "2026-10-11 01:00:00", "planned_end_date", "2026-10-11 02:00:00"),
                Map.of("short_description", "B", "order", "20", "assignment_group", "g",
                        "planned_start_date", "2026-10-11 01:30:00", "planned_end_date", "2026-10-11 07:00:00"));
        List<String> ids = engine.validate(f, tasks).stream().map(Violation::ruleId).toList();
        assertThat(ids).contains("HR-003", "HR-014", "HR-015");
    }

    @Test
    void conditionalRuleSkipsStandardChanges() {
        Map<String, String> f = goodFields();
        f.put("type", "standard");
        f.remove("backout_plan");
        f.remove("test_plan");
        List<String> ids = engine.validate(f, goodTasks()).stream().map(Violation::ruleId).toList();
        assertThat(ids).doesNotContain("HR-011", "HR-012");
    }

    @Test
    void maintenanceWindowRule() {
        Map<String, String> f = goodFields();
        // Sunday but runs past 06:00
        f.put("start_date", "2026-10-11 04:00:00");
        f.put("end_date", "2026-10-11 07:00:00");
        List<Violation> v = engine.validate(f, List.of());
        Violation w = v.stream().filter(x -> x.ruleId().equals("HR-018")).findFirst().orElseThrow();
        assertThat(w.message()).contains("outside the window");
        assertThat(w.severity()).isEqualTo("warn");   // the user may shift the window on purpose

        // Sunday 00:00 exactly is fine
        f.put("start_date", "2026-10-11 00:00:00");
        f.put("end_date", "2026-10-11 06:00:00");
        assertThat(engine.validate(f, List.of())).extracting(Violation::ruleId).doesNotContain("HR-018");
    }
}
