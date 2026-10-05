package com.company.cragent.tools;

import com.company.cragent.cockpit.CockpitStore;
import com.company.cragent.cockpit.RecordCache;
import com.company.cragent.config.CockpitProperties;
import com.company.cragent.config.InventoryProperties;
import com.company.cragent.config.KnowledgeProperties;
import com.company.cragent.inventory.Inventory;
import com.company.cragent.knowledge.FormCatalog;
import com.company.cragent.template.TemplateService;
import com.company.cragent.validation.RuleEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** "照着 CHG… 建一张": what is copied, what is dropped, what is cleared - the same for the page and for Copilot. */
class DraftFromChangeTest {

    @TempDir Path tmp;

    @Test
    void copiesContentDropsIdentityAndClearsDates() {
        ObjectMapper json = new ObjectMapper();
        CockpitProperties props = new CockpitProperties(tmp, 0, false);
        RecordCache cache = new RecordCache(props, json);
        ServiceNowToolsTest.RecordingClient client = new ServiceNowToolsTest.RecordingClient();
        ServiceNowTools sn = new ServiceNowTools(client, new RuleEngine(Path.of("knowledge/rules/hard-rules.yaml")), new CockpitStore(props, json), cache);
        TemplateTools tools = new TemplateTools(new TemplateService(Path.of("knowledge/templates")),
                new Inventory(new InventoryProperties("knowledge/inventory.sample.xlsx", "", null, "Sun 00:00-06:00")),
                sn, cache, new FormCatalog(new KnowledgeProperties(Path.of("knowledge")), json));

        Map<String, Object> old = new LinkedHashMap<>();
        old.put("number", "CHG0031234");
        old.put("state", "closed");
        old.put("approval", "approved");
        old.put("sys_id", "9f1c");
        old.put("short_description", "[PATCH] srv-app-01 - 2026-09 patches");
        old.put("backout_plan", "Restore snapshot");
        old.put("start_date", "2026-09-13 01:00:00");          // datetime in knowledge/forms/change.json
        old.put("servers", List.of("srv-app-01", "srv-app-02"));
        old.put("cmdb_ci", Map.of("value", "abc", "display_value", "srv-app-01"));
        client.store.put("CHG0031234", old);
        sn.getChange("CHG0031234");                              // leaves the local copy the tool reads

        var r = tools.draftFromChange("CHG0031234", null);
        Map<String, Object> f = r.draft().fields();
        assertThat(f).doesNotContainKeys("number", "state", "approval", "sys_id");
        assertThat(f).containsEntry("backout_plan", "Restore snapshot").containsEntry("start_date", "");
        assertThat(f.get("servers")).isEqualTo(List.of("srv-app-01", "srv-app-02"));         // type kept
        assertThat(f.get("cmdb_ci")).isEqualTo(Map.of("value", "abc", "display_value", "srv-app-01"));
        assertThat(r.notes()).anyMatch(n -> n.contains("start_date")).anyMatch(n -> n.contains("title"));
        assertThat(client.calls).containsExactly("get CHG0031234");                       // local copy used, no second read
    }
}
