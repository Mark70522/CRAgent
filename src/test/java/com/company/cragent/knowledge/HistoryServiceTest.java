package com.company.cragent.knowledge;

import com.company.cragent.cockpit.CockpitStore;
import com.company.cragent.cockpit.RecordCache;
import com.company.cragent.config.CockpitProperties;
import com.company.cragent.config.KnowledgeProperties;
import com.company.cragent.template.TemplateService;
import com.company.cragent.tools.ServiceNowTools;
import com.company.cragent.tools.ServiceNowToolsTest;
import com.company.cragent.validation.RuleEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Import exported records, group them by service + title pattern, write a template from a group. */
class HistoryServiceTest {

    @TempDir Path tmp;
    RecordCache cache;
    HistoryService history;
    KnowledgeProperties knowledge;

    @BeforeEach
    void setUp() {
        CockpitProperties cp = new CockpitProperties(tmp.resolve("cockpit"), 0, false);
        ObjectMapper json = new ObjectMapper();
        cache = new RecordCache(cp, json);
        ServiceNowTools sn = new ServiceNowTools(new ServiceNowToolsTest.RecordingClient(), new RuleEngine(Path.of("knowledge/rules/hard-rules.yaml")), new CockpitStore(cp, json), cache);
        knowledge = new KnowledgeProperties(tmp.resolve("knowledge"));
        history = new HistoryService(cache, sn, knowledge, json, "business_service");
    }

    static Map<String, Object> rec(String number, String service, String title, String group, String risk, String start, String end, List<Map<String, Object>> tasks) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("number", number); m.put("business_service", service); m.put("short_description", title); m.put("assignment_group", group); m.put("risk", risk);
        m.put("type", "normal"); m.put("start_date", start); m.put("end_date", end);
        m.put("description", "1. 变更对象\nsrv-app-01\n\n2. 影响范围\nOrder Portal 45 分钟\n\n3. 回退方案\n还原快照");
        if (tasks != null) m.put("tasks", tasks);
        return m;
    }

    @Test
    void signatureBlanksServersDatesAndNumbers() {
        assertThat(HistoryService.signature("[PATCH] srv-app-01, srv-app-02 - 2026-10 Windows monthly security patches")).isEqualTo("[patch] <x> - <date> windows monthly security patches");
        assertThat(HistoryService.signature("[PATCH] srv-db-07 - 2026-09 Windows monthly security patches")).isEqualTo("[patch] <x> - <date> windows monthly security patches");
        assertThat(HistoryService.signature("Release Order Portal v2.3.1 to prod")).isEqualTo("release order portal v<n> to prod");
        assertThat(HistoryService.signature("Release Order Portal v2.4.0 to prod")).isEqualTo("release order portal v<n> to prod");
        assertThat(HistoryService.patternToTemplate("[patch] <x> - <date> windows monthly security patches")).isEqualTo("[patch] {ci_list} - {summary} windows monthly security patches");
    }

    @Test
    void importGroupsAndTemplate() throws Exception {
        List<Map<String, Object>> tasks = List.of(
                Map.of("short_description", "Pre-check", "assignment_group", "Wintel Ops", "planned_start_date", "2026-09-13 01:00:00", "planned_end_date", "2026-09-13 01:30:00"),
                Map.of("short_description", "Patch", "assignment_group", "Wintel Ops", "planned_start_date", "2026-09-13 01:30:00", "planned_end_date", "2026-09-13 03:30:00"));
        Map<String, Object> r = history.importRecords(List.of(
                rec("CHG0001", "Order Portal", "[PATCH] srv-app-01 - 2026-08 Windows monthly security patches", "Wintel Ops", "Moderate", "2026-08-09 01:00:00", "2026-08-09 05:00:00", null),
                rec("CHG0002", "Order Portal", "[PATCH] srv-app-02 - 2026-09 Windows monthly security patches", "Wintel Ops", "Moderate", "2026-09-13 01:00:00", "2026-09-13 05:00:00", tasks),
                rec("CHG0003", "Order Portal", "Release Order Portal v2.3.1 to prod", "App Team", "High", "2026-09-20 22:00:00", "2026-09-21 00:00:00", null),
                rec("CHG0004", "CCS", "[PATCH] srv-ccs-01 - 2026-09 Windows monthly security patches", "Wintel Ops", "Low", "2026-09-13 01:00:00", "2026-09-13 05:00:00", null),
                Map.of("short_description", "no number")));
        assertThat(String.valueOf(r.get("imported"))).isEqualTo("[CHG0001, CHG0002, CHG0003, CHG0004]");
        assertThat((List<?>) r.get("failed")).hasSize(1);
        assertThat(cache.get("change", "CHG0002").orElseThrow().get("action")).isEqualTo("import");

        List<HistoryService.Group> groups = history.groups();
        assertThat(groups).hasSize(3);
        HistoryService.Group patch = groups.get(0);   // biggest first
        assertThat(patch.service()).isEqualTo("Order Portal");
        assertThat(patch.count()).isEqualTo(2);
        assertThat(patch.pattern()).isEqualTo("[patch] <x> - <date> windows monthly security patches");
        assertThat(patch.commonFields()).containsEntry("assignment_group", "Wintel Ops").containsEntry("risk", "Moderate").doesNotContainKey("short_description");
        assertThat(patch.fieldAgreement()).containsEntry("risk", 100);
        assertThat(patch.templateName()).isNull();

        Map<String, Object> t = history.templateFromGroup(patch.key(), null);
        Path file = Path.of(String.valueOf(t.get("file")));
        assertThat(file).exists();
        String yaml = Files.readString(file);
        assertThat(yaml).contains("name: order-portal-patch-x-date-windows-monthly-security-patches").contains("assignment_group: Wintel Ops")
                .contains("short_description_pattern:").contains("{ci_list} - {summary} windows monthly security patches").contains("duration_minutes: 120").contains("heading: 影响范围");

        // the template loads through the real TemplateService, i.e. create-cr can use it right away
        TemplateService ts = new TemplateService(knowledge.templatesDir());
        assertThat(ts.listTemplates()).extracting(x -> x.name()).contains("order-portal-patch-x-date-windows-monthly-security-patches");
        assertThat(ts.getTemplate("order-portal-patch-x-date-windows-monthly-security-patches").tasks()).hasSize(2);
        assertThat(history.groups().get(0).templateName()).isNotNull();

        String tn = String.valueOf(t.get("name"));
        // editing round-trip
        String edited = history.readTemplate(tn).replace("risk: Moderate", "risk: Low");
        history.writeTemplate(tn, edited);
        assertThat(history.readTemplate(tn)).contains("risk: Low");
        assertThat(history.deleteTemplate(tn)).isTrue();
    }
}
