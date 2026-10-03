package com.company.cragent.tools;

import com.company.cragent.cockpit.CockpitModel.Task;
import com.company.cragent.cockpit.CockpitStore;
import com.company.cragent.cockpit.RecordCache;
import com.company.cragent.config.CockpitProperties;
import com.company.cragent.model.ChangeDraft;
import com.company.cragent.model.ChangeRecord;
import com.company.cragent.model.TaskRecord;
import com.company.cragent.servicenow.ServiceNowClient;
import com.company.cragent.validation.RuleEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guards around the three ServiceNow operations, exercised with an in-memory ServiceNowClient that
 * lives only in this test. It records what it was asked to do; it is not sample data.
 */
class ServiceNowToolsTest {

    /** Minimal in-test client: remembers calls, returns what it was given. */
    static class RecordingClient implements ServiceNowClient {
        final List<String> calls = new ArrayList<>();
        final Map<String, Map<String, String>> store = new HashMap<>();
        public ChangeRecord getChange(String number) {
            calls.add("get " + number);
            Map<String, String> f = store.get(number);
            if (f == null) throw new com.company.cragent.servicenow.ServiceNowException("not found: " + number);
            return new ChangeRecord(number, f, List.of(), new ObjectMapper().valueToTree(f));
        }
        public ChangeRecord createChange(ChangeDraft draft) {
            calls.add("create " + draft.fields().get("short_description"));
            Map<String, String> f = new LinkedHashMap<>(draft.fieldsAsText());
            f.put("number", "CHG0099");
            store.put("CHG0099", f);
            return new ChangeRecord("CHG0099", f, draft.tasksAsText(), new ObjectMapper().valueToTree(f));
        }
        public ChangeRecord updateChange(String number, Map<String, Object> fields) {
            calls.add("update " + number + " " + fields);
            Map<String, String> f = store.get(number);
            fields.forEach((k, v) -> f.put(k, String.valueOf(v)));
            return new ChangeRecord(number, f, List.of(), new ObjectMapper().valueToTree(f));
        }
        public TaskRecord createTask(String changeNumber, Map<String, Object> fields) { calls.add("task+ " + changeNumber + " " + fields); return new TaskRecord("TASK1", changeNumber, Map.of("sys_id", "TASK1", "short_description", String.valueOf(fields.get("short_description"))), null); }
        public TaskRecord cancelTask(String taskId, Map<String, Object> fields) { calls.add("task- " + taskId); return new TaskRecord(taskId, null, Map.of("state", "cancelled"), null); }
        public TaskRecord closeTask(String taskId, Map<String, Object> fields) { calls.add("task# " + taskId); return new TaskRecord(taskId, null, Map.of("state", "closed"), null); }
        public String describe() { return "recording client"; }
    }

    @TempDir Path tmp;
    final RecordingClient client = new RecordingClient();
    CockpitStore cockpit;
    RecordCache cache;
    ServiceNowTools tools;

    @BeforeEach
    void setUp() {
        CockpitProperties props = new CockpitProperties(tmp, 0, false);
        cockpit = new CockpitStore(props, new ObjectMapper());
        cache = new RecordCache(props, new ObjectMapper());
        tools = new ServiceNowTools(client, new RuleEngine(Path.of("knowledge/rules/hard-rules.yaml")), cockpit, cache);
    }

    @Test
    void everyOperationLeavesALocalCopy() {
        assertThat(cache.list()).isEmpty();
        tools.createChange(goodDraft(), true, null);
        assertThat(cache.get("change", "CHG0099")).isPresent();
        assertThat(cache.get("change", "CHG0099").get().get("action")).isEqualTo("create");
        assertThat(cache.get("change", "CHG0099").get().get("title")).isEqualTo("[PATCH] srv-app-01 - 2026-10 Windows monthly security patches");
        assertThat(cache.list()).hasSize(1);
        assertThat(cache.list().get(0).get("passed")).isEqualTo(true);   // the created draft passed the rules

        tools.updateChange("CHG0099", Map.of("risk", "High"), true);
        Map<String, Object> rec = cache.get("change", "CHG0099").orElseThrow();
        assertThat(rec.get("action")).isEqualTo("update");
        @SuppressWarnings("unchecked") Map<String, Object> f = (Map<String, Object>) rec.get("fields");
        assertThat(f).containsEntry("risk", "High");

        tools.getChange("CHG0099");   // the in-test client returns no tasks on read, so the rule result changes, but it is still one file
        assertThat(cache.list()).hasSize(1);
        assertThat(cache.list().get(0).get("id")).isEqualTo("CHG0099");
        assertThat(cache.get("change", "CHG0099").orElseThrow().get("action")).isEqualTo("get");
    }

    static ChangeDraft goodDraft() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("short_description", "[PATCH] srv-app-01 - 2026-10 Windows monthly security patches");
        f.put("description", "1. 变更对象\nsrv-app-01 (prod, Windows Server 2019, Order Portal application server)\n\n2. 补丁清单及来源\n2026-10 Microsoft monthly security baseline from WSUS\n\n3. 影响范围\nOrder Portal front end unavailable for about 45 minutes, business owner informed\n\n4. 执行步骤\nsee change tasks: snapshot, patch, reboot, validate\n\n5. 验证方法\nIIS returns 200, health check OK, no critical events\n\n6. 回退方案\nrestore the VMware snapshot, about 20 minutes, rehearsed last month");
        f.put("type", "normal"); f.put("risk", "Moderate"); f.put("cmdb_ci", "srv-app-01"); f.put("assignment_group", "Wintel Ops");
        f.put("start_date", "2026-10-11 01:00:00"); f.put("end_date", "2026-10-11 05:00:00");
        f.put("justification", "Security compliance"); f.put("implementation_plan", "See tasks");
        f.put("backout_plan", "Restore snapshot, 20 min"); f.put("test_plan", "Health checks");
        List<Map<String, Object>> tasks = List.of(
                Map.of("short_description", "Pre-check", "order", "10", "assignment_group", "Wintel Ops", "planned_start_date", "2026-10-11 01:00:00", "planned_end_date", "2026-10-11 01:30:00"),
                Map.of("short_description", "Patch", "order", "20", "assignment_group", "Wintel Ops", "planned_start_date", "2026-10-11 01:30:00", "planned_end_date", "2026-10-11 03:30:00"));
        return new ChangeDraft(f, tasks);
    }

    @Test
    void createRefusesWithoutConfirmationAndWithRuleErrors() {
        assertThatThrownBy(() -> tools.createChange(goodDraft(), false, null)).hasMessageContaining("confirmation");
        ChangeDraft bad = goodDraft();
        bad.fields().put("short_description", "patch servers");
        assertThatThrownBy(() -> tools.createChange(bad, true, null)).hasMessageContaining("hard-rule error");
        assertThat(client.calls).isEmpty();
    }

    @Test
    void createThenReadThenUpdate() {
        Map<String, Object> created = tools.createChange(goodDraft(), true, null);
        assertThat(created.get("number")).isEqualTo("CHG0099");
        assertThat((Boolean) created.get("passed")).isTrue();

        Map<String, Object> read = tools.getChange("CHG0099");
        assertThat(read.get("number")).isEqualTo("CHG0099");

        assertThatThrownBy(() -> tools.updateChange("CHG0099", Map.of("risk", "High"), false)).hasMessageContaining("confirmation");
        Map<String, Object> updated = tools.updateChange("CHG0099", Map.of("risk", "High"), true);
        @SuppressWarnings("unchecked") Map<String, String> f = (Map<String, String>) updated.get("fields");
        assertThat(f).containsEntry("risk", "High");
        assertThat(client.calls).containsExactly("create [PATCH] srv-app-01 - 2026-10 Windows monthly security patches", "get CHG0099", "update CHG0099 {risk=High}");
    }

    @Test
    void createLinksTheCockpitTaskAndReadShowsIt() {
        Task t = new Task(); t.title = "给 srv-app-01 打十月补丁";
        String id = cockpit.addTasks(List.of(t), "paste").created().get(0).id;

        Map<String, Object> created = tools.createChange(goodDraft(), true, id);
        assertThat(((Task) created.get("task")).cr).isEqualTo("CHG0099");
        assertThat(cockpit.task(id).orElseThrow().cr).isEqualTo("CHG0099");
        assertThat(cockpit.taskNotes(id)).contains("CHG0099");

        assertThat(((Task) tools.getChange("CHG0099").get("task")).id).isEqualTo(id);

        // a wrong task id never turns a successful create into an error
        Map<String, Object> again = tools.createChange(goodDraft(), true, "T-9999");
        assertThat(again.get("number")).isEqualTo("CHG0099");
        assertThat(again).containsKey("taskLinkError");
    }

    @Test
    void taskOperationsNeedConfirmationAndAreCached() {
        assertThatThrownBy(() -> tools.createTask("CHG0099", Map.of("short_description", "Pre-check"), false)).hasMessageContaining("confirmation");
        Map<String, Object> t = tools.createTask("CHG0099", Map.of("short_description", "Pre-check"), true);
        assertThat(t.get("id")).isEqualTo("TASK1");
        assertThat(cache.get("task", "TASK1")).isPresent();
        assertThat(cache.tasksOf("CHG0099")).hasSize(1);
        tools.cancelTask("TASK1", null, true);
        assertThat(cache.get("task", "TASK1").orElseThrow().get("action")).isEqualTo("cancel");
        tools.closeTask("TASK1", Map.of("close_notes", "done"), true);
        assertThat(cache.get("task", "TASK1").orElseThrow().get("action")).isEqualTo("close");
        assertThat(cache.tasksOf("CHG0099")).hasSize(1);   // changeNumber survives updates that do not carry it
        assertThat(client.calls).containsExactly("task+ CHG0099 {short_description=Pre-check}", "task- TASK1", "task# TASK1");
    }

    @Test
    void readErrorsAreNotSwallowed() {
        assertThatThrownBy(() -> tools.getChange("CHG0000")).hasMessageContaining("not found: CHG0000");
    }
}
